# DinoParty

Browser party games for friends (Draw & Guess, Telephone Drawing). This repository currently contains the **Phase 1 foundation** plus **Phase 2 (guest identity and rooms)**: a Spring Boot backend with PostgreSQL + Flyway and a React frontend where players create a guest profile (nickname + dino, no account), create or join a room by code or invite link, and wait in a polled lobby. The backend now also has the realtime layer (Phase 3) and the shared game engine (Phase 4, tested with a test-only game; no real game is playable yet). See `docs/implementation-plan.md` for the roadmap and `AGENTS.md` for the working agreement.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | `java -version` must report 21. Maven itself is not needed (wrapper included). |
| Node.js | 22 LTS (or newer) | with npm |
| Docker | recent, with Compose v2 | runs PostgreSQL 16 for development and for the integration tests |

## Ports

| Service | Port |
|---|---|
| Frontend (Vite) | 5173 |
| Backend (Spring Boot) | 8080 |
| PostgreSQL (host side) | 5433 → container 5432 |

## First-time setup

```bash
cp .env.example .env          # Windows PowerShell: Copy-Item .env.example .env
# edit .env: set POSTGRES_PASSWORD and DB_PASSWORD to the same value
npm --prefix frontend ci
```

`.env` is git-ignored. `.env.example` contains placeholders only.

## Run everything with one command

```powershell
.\scripts\dev.ps1                  # Windows: database + backend + frontend, Ctrl+C stops the apps
.\scripts\dev.ps1 -Observability   # also Collector + Aspire Dashboard (http://localhost:18888): backend, browser and Postgres telemetry
.\scripts\dev.ps1 -Stop            # stop the Docker services
```

```bash
scripts/dev.sh [--observability|--stop]   # macOS/Linux/Git Bash
```

It creates `.env` from `.env.example` if missing, installs frontend dependencies on first run and waits for the database to be healthy. App: <http://localhost:5173>, API: <http://localhost:8080/api/v1/health>. Needs Docker, JDK 21 and Node. The manual three-terminal steps below do the same thing.

## Run the stack (three terminals)

```bash
# 1. Database (from repo root). Data persists in the named volume `dinoparty-dev_dinoparty-db-data`.
docker compose --env-file .env -f deploy/compose.dev.yml up -d db

# 2. Backend (profile `dev` reads ../.env). Flyway migrates on startup.
./backend/mvnw -f backend spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Frontend, then open http://localhost:5173
npm --prefix frontend run dev
```

Windows PowerShell uses the same commands with `.\backend\mvnw.cmd` instead of `./backend/mvnw`. If `JAVA_HOME` points at another JDK, set it to JDK 21 first (`$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`).

Check it:

```bash
curl -fsS http://localhost:8080/api/v1/health
# {"status":"UP","version":"0.0.1-SNAPSHOT","db":"UP","time":"..."}
curl -fsS http://localhost:8080/actuator/health/liveness
curl -fsS http://localhost:8080/actuator/health/readiness
```

With the database stopped (`docker compose --env-file .env -f deploy/compose.dev.yml stop db`) the health endpoint answers `503` with `"status":"DOWN","db":"DOWN"`, and recovers once the database is back.

Optional: local traces, metrics and logs from backend, frontend and Postgres in the Aspire Dashboard (<http://localhost:18888>) - see [docs/observability.md](docs/observability.md).

Stop the database: `docker compose --env-file .env -f deploy/compose.dev.yml down` (keeps data). Add `-v` to delete the data volume.

In dev the frontend calls `/api/...` on its own origin and Vite proxies it to `localhost:8080`, so CORS is not involved locally. `CORS_ALLOWED_ORIGINS` (comma-separated, no wildcards) matters when the frontend is served from another origin.

## Build and test

```bash
# Backend: unit tests (*Test) + integration tests (*IT, need Docker for Testcontainers) + Spotless check
./backend/mvnw -f backend verify
./backend/mvnw -f backend test           # unit tests only, no Docker
./backend/mvnw -f backend spotless:apply # auto-format

# Frontend
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build          # outputs frontend/dist
npm --prefix frontend run format         # prettier --write
```

CI (`.github/workflows/ci.yml`) runs the backend `verify` and the frontend format check, lint, typecheck, test and build on every PR.

## Layout

```
backend/    Spring Boot 4 (Java 21) modular monolith: common, guest, room, health, web (more modules arrive with their phase)
frontend/   React + TypeScript (strict) + Vite, Tailwind v4, shadcn/ui-style components, React Router, Zustand, TanStack Query
deploy/     compose.dev.yml (PostgreSQL for development), Dockerfile (production image; see docs/deployment.md)
docs/       requirements, architecture, contracts, rules, plan
```

## Troubleshooting

- **`release version 21 not supported`** — Maven is running on an older JDK. Point `JAVA_HOME` at JDK 21.
- **Backend fails with `password authentication failed`** — `DB_PASSWORD` in `.env` differs from `POSTGRES_PASSWORD` that the database volume was first created with. Fix `.env`, or recreate the volume with `down -v`.
- **`POSTGRES_PASSWORD` is required** from Compose — pass `--env-file .env` (Compose does not read the repo-root `.env` automatically when the compose file lives in `deploy/`).
- **Port 5433/8080/5173 already in use** — stop the other process or change `POSTGRES_PORT` / `server.port`.
- **Integration tests fail to start** — Docker Desktop must be running (Testcontainers pulls `postgres:16-alpine`).
- **Frontend shows "Backend unreachable"** — the backend is not running on 8080.
- A harmless `Could not transfer metadata ... flyway-community-db-support ... 401` warning may appear on the first Maven run.

## Known limitations (Phases 1-2)

See `docs/testing.md` for what has and has not been verified.
