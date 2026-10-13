# Local observability (optional, development only)

Backend, browser and PostgreSQL telemetry flow into one place, the standalone
[.NET Aspire Dashboard](https://learn.microsoft.com/dotnet/aspire/fundamentals/dashboard/standalone),
through a small OpenTelemetry Collector. It is **off by default**; nothing changes for tests, CI or production unless you opt in.

```
Spring Boot  --OTLP/HTTP-->                     +-----------+      +------------------+
React (browser) --OTLP/HTTP (CORS allowlist)--> | Collector | ---> | Aspire Dashboard |
PostgreSQL JSON log file (shared volume) -----> +-----------+      |  localhost:18888 |
Vite dev server: terminal only                                      +------------------+
```

| Resource in Aspire | Source | Signals |
|---|---|---|
| `dinoparty-backend` | Spring Boot | traces, metrics, logs |
| `dinoparty-frontend` | Browser | traces (fetch), logs (uncaught errors, unhandled rejections) |
| `dinoparty-postgres` | PostgreSQL log file | logs |

## Start

One command (also starts backend and frontend with telemetry enabled):

```powershell
.\scripts\dev.ps1 -Observability      # macOS/Linux/Git Bash: scripts/dev.sh --observability
.\scripts\dev.ps1 -Stop               # stops database, Collector and dashboard
```

Or by hand:

```bash
docker compose --env-file .env -f deploy/compose.dev.yml -f deploy/compose.observability.yml up -d
OTEL_ENABLED=true ./backend/mvnw -f backend spring-boot:run -Dspring-boot.run.profiles=dev
VITE_OTEL_ENABLED=true npm --prefix frontend run dev
```

Open <http://localhost:18888> (no login). Pick the resource in the dropdown; use **Structured logs**, **Traces** and **Metrics**.
Data lives in dashboard memory and is lost when the container restarts. Telemetry takes about 5-15 s to appear.

## Configuration

| Variable | Default | Where | Meaning |
|---|---|---|---|
| `OTEL_ENABLED` | `false` | backend | Master switch for backend trace, metric and log export. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | backend | Collector base URL (`/v1/traces`, `/v1/metrics`, `/v1/logs` are appended). |
| `OTEL_SERVICE_NAME` | `dinoparty-backend` | backend | Resource name in Aspire. |
| `OTEL_TRACES_SAMPLING_PROBABILITY` | `1.0` | backend | Fraction of requests traced. |
| `OTEL_METRICS_EXPORT_INTERVAL` | `15s` | backend | Metric push interval. |
| `VITE_OTEL_ENABLED` | unset (off) | frontend | `true` turns on browser telemetry. Set in the shell or `frontend/.env.local` (Vite does not read the root `.env`). |
| `VITE_OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | frontend | Collector URL the browser posts to. |
| `OTEL_ALLOWED_ORIGIN` | `http://localhost:5173` | Collector | The only browser origin allowed to post telemetry (exact match, no wildcard). |
| `OTEL_COLLECTOR_HTTP_PORT` | `4318` | compose | Host port of the Collector (loopback only). |
| `ASPIRE_UI_PORT` | `18888` | compose | Host port of the dashboard (loopback only). |

Production: none of these are set, so no SDK is loaded in the browser and no exporter is active in the backend. Do not deploy `compose.observability.yml`.

## What is recorded, and what is not

- **Backend:** REST request spans (route template, method, status), `dinoparty.room.*` spans with `room.code`, WebSocket lifecycle (`dinoparty.ws.connect` and `dinoparty.ws.command` spans with the command `type` as the only low-cardinality tag; `dinoparty.ws.connections` / `dinoparty.ws.commands` / `dinoparty.ws.disconnects` counters, `dinoparty.ws.connections.active` and `dinoparty.ws.players.disconnected` gauges), JVM/HTTP/Hikari metrics, application logs with `roomCode`/`guestId`/`sessionId` MDC attributes when set. Headers, bodies and query strings are not recorded, so no `Authorization` token.
- **Frontend:** `fetch` spans (URL, method, status; no headers or bodies, so no token) and error logs with type, message (max 300 chars), stack (max 2000 chars). Query strings and fragments are stripped from messages and stack traces, and non-`Error` rejection values are never serialized. Drawing payloads are never captured because only fetch metadata and error text are collected. Same-origin requests carry a `traceparent` header, so a browser request and the backend span share a trace.
- **PostgreSQL:** server log messages (errors, startup/shutdown, checkpoints). Statements are never logged (`log_min_error_statement=panic`), and the Collector additionally deletes `statement`, `detail`, `query`, `internal_query` and `context` fields before forwarding, since they can contain row values.

Rule for new code: never put tokens, secret words, drawing data or free-form user input into log lines, span attributes or error messages.

## How PostgreSQL logs reach the Collector

The Collector cannot read Docker's container logs, so it does not try. `compose.observability.yml` overrides the `db` command so PostgreSQL writes JSON logs (`logging_collector=on`, `log_destination=jsonlog`) to `<data dir>/log/postgresql.json` on its data volume. The Collector mounts the same volume read-only and tails that file with the `filelog` receiver, tagging records `service.name=dinoparty-postgres`.

Consequences: `docker compose logs db` is empty while the override is active (read the file, or Aspire). The Collector runs as root, development only, because PostgreSQL's data directory is mode 0700. Start/stop the override by re-running `up -d`; the db container is recreated automatically when the command changes (data is kept).

## Vite dev-server logs

They stay in the terminal. Forwarding them would need an explicit mechanism (for example piping the dev server's stdout to a file the Collector tails); none is configured.

## Verify it works

1. **Backend:** `curl -X POST localhost:8080/api/v1/guests -H 'content-type: application/json' -d '{}'`, or just restart the backend; startup logs and the request trace appear under `dinoparty-backend`.
2. **Frontend:** with the app open at <http://localhost:5173>, run in the browser console `setTimeout(() => { throw new Error('probe') })`; an `Error: probe` record appears under `dinoparty-frontend` > Structured logs.
3. **PostgreSQL:** `docker exec dinoparty-dev-db-1 psql -U dinoparty -d dinoparty -c "select * from no_such_table"`; `relation "no_such_table" does not exist` appears under `dinoparty-postgres`, without the SQL text.

## Troubleshooting

- **Nothing appears at all:** check `docker ps` (collector and dashboard `Up`), and that `OTEL_ENABLED=true` / `VITE_OTEL_ENABLED=true` reached the process. Collector log: `docker logs dinoparty-dev-otel-collector-1`.
- **Collector says "Exporting failed ... 500":** the dashboard rejected the payload; the Collector must use `compression: none` towards Aspire (already set).
- **Frontend telemetry missing, browser console shows a CORS error:** the page origin differs from `OTEL_ALLOWED_ORIGIN` (e.g. `127.0.0.1` vs `localhost`, or another port). Set the variable to the exact origin and restart the Collector (`docker compose ... restart otel-collector`).
- **No `dinoparty-postgres` logs:** the `db` container must have been recreated with the override (`docker exec dinoparty-dev-db-1 ls /var/lib/postgresql/data/log`). Start it via `-Observability` or both `-f` files.
- **Port already in use:** change `ASPIRE_UI_PORT` / `OTEL_COLLECTOR_HTTP_PORT` and the matching endpoint variables.
- **Backend started before the Collector:** records emitted while it was unreachable are dropped; restart the backend.
- **Resource still named `dinoparty`:** that backend was started before the rename to `dinoparty-backend`; restart it.

## Limitations

- Dashboard and Collector are unauthenticated and loopback-only; use for local development, never expose them.
- Telemetry is in memory only (restart clears it); no alerting or long-term storage.
- Browser telemetry covers `fetch` and uncaught errors only; React render errors are not caught unless they escape to `window`. No page-load or user-interaction instrumentation.
- Frontend tracing/error code lives in `frontend/src/telemetry/` and was added by Claude Code for this task; AGENTS.md assigns `frontend/` to Antigravity, so treat the PR note as a request for their review.
