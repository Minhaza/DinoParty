# DinoParty — Testing

Skeleton created in Phase 1; sections are filled as the phases land. Rules and requirements: `AGENTS.md` §6, plan: `docs/implementation-plan.md`.

## Backend (CC)

| Kind | Naming | Runner | Needs Docker |
|---|---|---|---|
| Unit | `*Test` | surefire (`./backend/mvnw -f backend test`) | no |
| Integration | `*IT` | failsafe (`./backend/mvnw -f backend verify`) | yes (Testcontainers `postgres:16-alpine`, one shared container per JVM via `PostgresTestConfig`) |
| Architecture | `ArchitectureTest` | surefire | no |

Current coverage (Phase 1): health service/probe units, `GET /api/v1/health` (200 and 503), Flyway baseline applied to an empty database, Actuator liveness/readiness, deny-by-default security (401 problem+json, non-health actuator endpoints denied), CORS preflight allow/deny from env allowlist, ArchUnit module and injection rules (violation proven to fail with a temporary forbidden dependency).

## Frontend (AG)

Vitest + Testing Library (`npm --prefix frontend test`). Current coverage (Phase 1): health API client (200, 503-as-DOWN, bad shape, error), system-status widget (UP/DOWN/unreachable), routing and placeholder labelling, theme resolution. Playwright E2E arrives with the phases that have flows to test.

## Phase 1 verification record

Run on Windows 11, JDK 21.0.3, Node 22, Docker Desktop.

| Check | Result |
|---|---|
| `./backend/mvnw -f backend verify` | passed: 8 unit tests, 10 integration tests, Spotless check |
| `npm --prefix frontend run lint` / `typecheck` / `test` / `build` / `format:check` | passed; 13 tests |
| `docker compose ... up -d db` | healthy; Flyway `V1` applied; data survived `down` then `up` |
| `spring-boot:run` (profile `dev`) against compose PostgreSQL | started, migrated, `GET /api/v1/health` → 200 `UP`/`UP` |
| Database stopped | `GET /api/v1/health` → 503 `DOWN`/`DOWN` after ~3 s; recovered to 200 after restart |
| Frontend dev server + Vite proxy to backend | `/api/v1/health` proxied; headless Edge screenshot showed "Backend UP" |

### Not verified / known limitations

- **Linux and CI:** not run. The workflow `.github/workflows/ci.yml` is untested until the first PR runs it. `backend/mvnw` has the executable bit set in git for Linux runners but that was not exercised.
- **`ddl-auto=validate`:** configured, but there are no JPA entities yet, so schema drift cannot fail startup until Phase 2 adds entities. Flyway migrations are applied and tested.
- **shadcn CLI:** `npx shadcn init` could not run (npm registry resolution failed for transitive dependencies). `components.json`, `lib/utils.ts`, the CSS-variable theme and the `Button`/`Card` components were written by hand in the CLI's output format. Later `npx shadcn add` should work once the registry resolves; re-check then.
- **Dependency pins:** `react`/`react-dom` pinned to 19.2.6, `@tailwindcss/vite`/`tailwindcss` to 4.3.0 and `vitest` to 4.x because the newest releases had unresolved transitive dependencies on the registry at the time.
- **`traceId`** in error bodies is a random UUID logged with 500 errors; there is no distributed tracing.
- Browser check was one headless Edge screenshot in dark mode (system theme); no cross-browser, mobile or accessibility (axe) runs.
- Docker-based production images, Playwright E2E and the WebSocket layer are later phases.
