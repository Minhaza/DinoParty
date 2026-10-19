# P7-03 — E2E regression gate (trimmed plan)

Replaces the scope of `docs/p7-03-plan.md`. That plan was audited against the repo on 2026-10-19:
most of its scenarios already exist (see §1), and the rest are covered at a cheaper layer. What
remains is to make the existing Playwright suite **run on every PR**, re-verify it after P7-02,
and close two small gaps.

Owners: CI job → Claude Code (`.github/`); spec and config edits → Antigravity lane
(`frontend/e2e/`, `frontend/playwright.config.ts`), done by whoever picks up the task with a note
in the PR. Branch: `feature/p7-03-e2e-ci` off `develop`. One PR.

## 1. Coverage audit (what is not redone)

| Scenario | Where it is covered | P7-03 action |
|---|---|---|
| Draw & Guess full game, drawing sync, guessing, rotation, server-timed round end, results, rematch | `e2e/drawguess.e2e.ts` (2 contexts, desktop + Pixel 7 touch) | Keep. 3rd player not added: it only adds the "others still guessing" case, covered by `[DG-n]` unit tests |
| Draw & Guess reconnect (reloads + network cut), secret word not in guesser DOM | `drawguess.e2e.ts` | Keep |
| Telephone 4-player game, distinct private prompts, alternating steps, reveal, albums, rematch | `e2e/telephone.e2e.ts` | Keep; add registry assertions (§3.2) |
| Telephone reconnect restores task and draft; reveal reload | `telephone.e2e.ts` | Keep |
| Submission recovery: lost submit, lost ACK, lost submit + socket drop, no duplicate | `e2e/telephone-submit-recovery.e2e.ts` | Keep |
| Telephone privacy at frame/snapshot level | `TelephoneIT` + raw-STOMP probe (testing.md P6 record) | Keep; not duplicated in Playwright |
| Non-host / wrong-phase / cross-room / forged `guestId` rejection | `DrawGuessIT`, `TelephoneIT`, the room/WebSocket authorization ITs, raw-STOMP probe | Keep at IT layer (server is what rejects; E2E would only re-send the same frames, slower) |
| Stale session after rematch | rematch steps in both specs assert new `sessionId`, zero scores, empty editors/canvas | Keep |
| Game list, player limits, settings form from `GET /games*`; disabled/unknown games hidden | `RoomForms.test.tsx` (component, §2.6 shapes) | Add one live-backend check (§3.2). Disabled/unknown games stay component-only: the real registry has none, and no test-only endpoint will be added |
| Accessibility smoke (axe) | — | Out of scope: roadmap task 7.2 |
| Playwright in CI | — | **Add (§2)** |

## 2. CI: run Playwright on every PR

Add a third job `e2e` to `.github/workflows/ci.yml` (no new workflow). Triggers stay as they are
(`pull_request` with no branch filter already covers PRs into `develop` and `main`).

Job outline:

- `runs-on: ubuntu-latest`, `timeout-minutes: 20`, no `needs` (runs in parallel with the others).
- `services.postgres`: `postgres:16-alpine`, `POSTGRES_DB/USER=dinoparty`, a throw-away
  `POSTGRES_PASSWORD` (CI-only, not a secret), port `5432:5432`, `pg_isready` health options.
- Job `env`: `DB_URL=jdbc:postgresql://localhost:5432/dinoparty`, `DB_USER`, `DB_PASSWORD`,
  `CORS_ALLOWED_ORIGINS=http://localhost:5173`, `APP_PUBLIC_URL=http://localhost:5173`, `CI=true`.
- Steps:
  1. `actions/checkout@v4`; `setup-java@v4` (temurin 21, maven cache); `setup-node@v4`
     (22, npm cache on `frontend/package-lock.json`).
  2. `./mvnw -B -ntp -DskipTests package` in `backend/` (tests already run in the `backend` job).
  3. Start the jar in the background, stdout/stderr to `backend.log`; poll
     `http://localhost:8080/api/v1/health` until `UP` (≤ 90 s, fail with the log tail otherwise).
  4. `npm ci` and `npx playwright install --with-deps chromium` in `frontend/`
     (cache `~/.cache/ms-playwright` keyed on the Playwright version).
  5. `npm run e2e` (the config starts the Vite dev server, which proxies `/api` and `/ws`).
  6. `if: failure()` → `actions/upload-artifact@v4` with `frontend/playwright-report/`,
     `frontend/test-results/` (traces + screenshots) and `backend.log`; retention 7 days.
     Failure triage = trace (browser/frontend/STOMP frames) + `backend.log` (server, Flyway,
     PostgreSQL); no extra logging added.

`playwright.config.ts` changes (minimal):

- `forbidOnly: !!process.env.CI`.
- `reporter`: add `['github']` when `CI` is set; keep `list` + `html`.
- `retries` stays 0 (a flaky test is a bug, AGENTS.md §6). `workers: 1` stays.
- `webServer.reuseExistingServer: !process.env.CI`.

## 3. Spec changes

### 3.1 Re-verify after P7-02 (first step)

Run the full suite locally against the dev backend before changing anything. No `e2e/` file
changed since P6-05, but P7-02 replaced the create-room game picker. Fix any breakage in the
spec, or, if the app is wrong, follow the defect rule (reproduce → regression test → smallest fix).

### 3.2 Live game-registry check (`telephone.e2e.ts`, create-room step)

The Telephone spec already creates its room through the UI. Before clicking the game:

- `GET /api/v1/games` through `request`; for every enabled game assert a picker button named
  after it is visible and shows `${minPlayers}–${maxPlayers} players`.
- After choosing Dino Telephone: its button has `aria-pressed="true"`, the other `false`; the
  settings form shows a field for each key of `GET /games/telephone` `settingsSchema` (by label)
  and the "Max players" options match its bounds.

No mocking; this is the backend → API → picker → room-creation path in one place.

### 3.3 Remove the timer-based negative check (`drawguess.e2e.ts:144`)

`waitForTimeout(500)` waits for "nothing happened" after the guesser's drag. Replace it with a
deterministic check: record the guesser page's outgoing WebSocket frames (`page.on('websocket')`
→ `framesent`) during the drag and assert none is a `SEND` with a `DRAW_STROKE` command. Keep
the existing ink assertions. The server-side `NOT_DRAWER` rejection stays covered by `DrawGuessIT`.

`telephone.e2e.ts:243` (`waitForTimeout(250)` before `REVEAL_NEXT`) stays: it paces clicks under
the documented 5/s rate limit (api-contracts §3.10) and is not a synchronization wait. Rate
limiting is not changed.

### 3.4 Isolation check

Each spec already creates its own guests (random nickname suffix) and room. With `workers: 1`
and a fresh CI database there is no cross-test state; confirm no spec depends on another's room
or on execution order (run each spec alone once).

## 4. Out of scope

New game behavior, test-only endpoints, rate-limit changes, axe/accessibility (7.2), mobile
emulation matrix (7.3), Firefox/WebKit, Docker images (8.2), a second E2E environment.

## 5. Verification

Locally (Windows, dev backend via `scripts/dev.sh` or `dev.ps1`):

```
./backend/mvnw -f backend verify
npm --prefix frontend run format
npm --prefix frontend run format:check
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run e2e
npm --prefix frontend run e2e -- --repeat-each=3
```

In CI: the PR's `e2e` job green, plus one deliberately failing run (temporary commit, reverted
before review) to confirm the report, traces and `backend.log` are uploaded.

## 6. Docs

- `docs/testing.md`: replace "not yet in CI" with the CI job description (services, how the app
  is started, artifacts, how to triage); add a "P7-03 verification record" with the commands
  and results actually run.
- `docs/implementation-plan.md`: add the P7-03 row below P7-02, and note that it delivers the
  E2E job part of 8.3.
- Delete or archive `docs/p7-03-plan.md` once this plan is accepted.

## 7. Definition of done

- [ ] Existing 3 specs pass locally after P7-02 (and `--repeat-each=3`).
- [ ] Registry assertions added and passing against the real backend.
- [ ] `drawguess.e2e.ts` has no `waitForTimeout`.
- [ ] `e2e` CI job green on the PR; failure artifacts verified once.
- [ ] Backend `verify`, frontend format/lint/typecheck/test/build green.
- [ ] `testing.md` and `implementation-plan.md` updated with what was actually run.
- [ ] Final report: READY FOR REVIEW or BLOCKED — reason.
