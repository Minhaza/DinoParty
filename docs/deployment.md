# Deployment — Render (decision D49)

One Docker web service serves the built frontend, the REST API (`/api/v1`) and STOMP (`/ws`) from
a single origin; Render PostgreSQL stores the data. Files: `deploy/Dockerfile` (multi-stage: Vite
build, Maven package, JRE runtime), `render.yaml` (Blueprint), `.dockerignore`.

## First deploy

1. Push the branch that contains `render.yaml` to GitHub and merge it into the branch the Blueprint
   deploys from (`branch: main` in `render.yaml`).
2. In the Render dashboard: **New → Blueprint**, connect the repository and pick that branch. Render
   lists the `dinoparty` web service and the `dinoparty-db` database (both `free`).
3. Render asks for the two `sync: false` values. The service URL is
   `https://<service-name>.onrender.com`, normally `https://dinoparty.onrender.com`; Render adds a
   suffix if the name is taken, so check the service page and correct the values if needed:
   - `CORS_ALLOWED_ORIGINS` = that URL (no trailing slash). The WebSocket endpoint checks `Origin`
     against this list even on the same origin, so a wrong value means "connection lost" in rooms.
   - `APP_PUBLIC_URL` = the same URL (used in invite links).
4. Apply. The first build takes several minutes (npm + Maven downloads). The deploy is live when
   `/actuator/health/readiness` answers 200; Flyway migrates the empty database on first start.
5. Check: open the URL, `GET /api/v1/health` shows `"db":"UP"`, create a room in one browser and
   join it from another (a private window works).

Later pushes to the deploy branch rebuild and redeploy automatically.

## Configuration

| Variable | Set by | Notes |
|---|---|---|
| `PORT` | `render.yaml` (`8080`) | `server.port`; Render routes to it. |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `fromDatabase` | Assembled into the JDBC URL; `DB_URL` would override them. Uses the internal address (same region). |
| `FORWARD_HEADERS_STRATEGY` | `render.yaml` (`native`) | Client address for rate limits from `X-Forwarded-For`, trusting private-range proxies only (Tomcat RemoteIpValve, right-most untrusted hop). Leave `none` anywhere clients reach the app without a proxy. |
| `CORS_ALLOWED_ORIGINS`, `APP_PUBLIC_URL` | you (dashboard) | See step 3. Add a custom domain here too if you attach one. |
| `SPA_ENABLED`, `SPA_LOCATION`, `JAVA_TOOL_OPTIONS` | `deploy/Dockerfile` | Serve `/app/static`; small-heap JVM flags. |
| `TOKEN_TTL`, `WS_DISCONNECT_GRACE`, `OTEL_*` | optional | Defaults as in `.env.example`; telemetry stays off. |

## Build the image locally

```bash
docker build -f deploy/Dockerfile -t dinoparty .
```

Run it against the dev database (`deploy/compose.dev.yml`) with the Render memory limit:

```bash
docker run --rm -p 8080:8080 --memory=512m -e DB_URL=jdbc:postgresql://host.docker.internal:5433/dinoparty -e DB_USER=dinoparty -e DB_PASSWORD=change-me -e CORS_ALLOWED_ORIGINS=http://localhost:8080 -e APP_PUBLIC_URL=http://localhost:8080 dinoparty
```

Then open `http://localhost:8080`.

## Free-tier limitations

- **Sleeps when idle** (R32): after about 15 minutes without inbound traffic the service stops; the
  next visit waits for a cold start (Spring Boot on a fraction of a CPU: expect up to a minute or
  more). Stopping ends live games (D13): sessions become `INTERRUPTED`, rooms return to the lobby.
- **Deploys overlap** (R33): Render starts the new instance before stopping the old one, so a
  deploy during a game can interrupt it twice over. Deploy when nobody is playing.
- **Database expires after 30 days, no backups** (R34). Upgrade the database plan before keeping
  real history.
- 512 MB memory. Watch the service's memory graph; `-XX:+ExitOnOutOfMemoryError` makes an OOM a
  clean restart rather than a stuck process.
- Single instance only (architecture §8): never scale the service above one instance.

## Not covered yet

- Security headers (CSP, HSTS, `X-Content-Type-Options`) that Caddy would have added in the
  original plan; Spring Security's defaults apply.
- CI does not build the image; a broken Dockerfile shows up as a failed Render deploy.
- The original Oracle VM + Caddy + Cloudflare Pages plan (Phase 8 tasks 8.2–8.4) is parked, not
  removed.
