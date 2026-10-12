# AGENTS.md — DinoParty working agreement

Applies to every AI agent and human contributor (Claude Code, Antigravity, others). Read this first, then `docs/MASTER_PROMPT.md` and `docs/architecture.md`.

## 1. Product and naming

- The product is **DinoParty**. The master prompt says "PartyPlay"; that name is superseded everywhere (code, UI, docs, package names, `com.dinoparty`).
- All branding, art, words and copy must be original.

## 2. Source-of-truth hierarchy

When documents disagree, the higher one wins; fix the lower one in the same change.

1. `docs/MASTER_PROMPT.md` — requirements (read-only; never edit; only the PartyPlay→DinoParty rename is overridden).
2. `docs/decisions-and-risks.md` — approved decisions (items marked approved override earlier text).
3. `docs/api-contracts.md` — REST/STOMP contracts. **Single source of truth** for DTOs, events, error codes.
4. `docs/game-rules.md` — game behavior; rule IDs (`[DG-n]`, `[TP-n]`, `[C-n]`) are referenced by tests.
5. `docs/architecture.md` — structure and design.
6. `docs/implementation-plan.md` — task order and acceptance criteria.
7. Code. If code and docs disagree, either fix the code or update the doc **in the same PR**; never leave silent drift.

Contract changes: edit `api-contracts.md` (and `game-rules.md` if behavior changes) in a separate commit named `docs(contract): …` **before** implementing it. Only Claude Code merges contract edits; Antigravity requests them in the PR description or `docs/contract-requests.md`.

Do not claim a feature works unless you ran its build/tests/browser check. State what was and wasn't verified.

## 3. Technology (do not change without an approved decision)

Backend: Java 21, Spring Boot (version pinned in `backend/pom.xml`), Maven wrapper, Spring MVC/WebSocket+STOMP/Data JPA/Security, PostgreSQL, Flyway, Bean Validation, JUnit 5, Mockito, Testcontainers.
Frontend: React + TypeScript (strict) + Vite, Tailwind v4, shadcn/ui, Zustand, TanStack Query, React Router, Canvas + Pointer Events, STOMP client, Lucide, Vitest + Testing Library, Playwright.
Modular monolith, single instance. No microservices, Kafka, Kubernetes, or message brokers. Redis only behind ports, never required.

## 4. Coding conventions

### Backend
- Package root `com.dinoparty.<module>`; respect module boundaries in `docs/architecture.md` §3.1 (enforced by ArchUnit). No cross-module access to `internal`/`persistence` packages.
- Controllers/handlers: validate → map to command → call service → map to DTO. No business logic.
- Never expose JPA entities; use records for DTOs/commands/events. Constructor injection only; no field injection, no static state.
- All state mutation for a room goes through the room mailbox. No `synchronized` ad-hoc locking elsewhere; no shared mutable collections across rooms.
- Time via injected `Clock`; randomness via injected `RandomSource`. No `Instant.now()`/`new Random()` in domain code.
- Never trust client input: validate every REST DTO and STOMP payload (`@Valid`), enforce sizes, check authorization from the authenticated principal, never from client-sent IDs.
- Schema changes only through Flyway migrations (immutable once merged; new file for fixes). `ddl-auto=validate`.
- Errors: throw domain exceptions mapped to problem+json codes in `api-contracts.md`. Do not leak stack traces.
- Logging via SLF4J, never log tokens or secrets; include `roomCode`/`guestId` MDC.
- Formatting: Spotless/google-java-format (configured in Phase 1). 

### Frontend
- TypeScript `strict`, no `any` (use `unknown` + narrowing). ESLint + Prettier clean.
- Server state → TanStack Query; local/real-time state → Zustand. Never copy server-authoritative values into a second store. Game/room state is replaced wholesale by snapshots.
- Canvas rendering is imperative (refs); do not store per-point data in React state.
- Subscriptions/listeners are created in effects and always cleaned up; sending commands includes `actionId`.
- Types for DTOs/events mirror `api-contracts.md`; discriminated unions on `type`.
- Components: small, accessible (labels, focus-visible, keyboard, `aria-live` for announcements, `prefers-reduced-motion`), no purely decorative controls.
- No `dangerouslySetInnerHTML`; render user text as text.

### General
- Small vertical slices; no dead scaffolding or placeholder classes without a task referencing them.
- No credentials, tokens or production secrets in git. `.env` ignored; `.env.example` placeholders only.
- Comments explain *why*; match surrounding style.
- Conventional commits: `feat(scope): …`, `fix`, `docs`, `test`, `chore`, `refactor`, `ci`.

## 5. Git and worktree boundaries

- `main` is protected: changes land through PRs (squash merge). Never commit directly to `main`; never force-push shared branches.
- **One task = one branch = one worktree.** Branch names `feature/<short-task-name>`, `fix/…`, `docs/…`. Do not reuse a worktree for a different task.
- Run commands only inside your own worktree; do not `cd` to another worktree or the primary checkout; do not edit files there.
- Ownership by path (edit outside your lane only with an explicit note in the PR):

| Path | Owner |
|---|---|
| `backend/`, `deploy/`, `.github/` | Claude Code |
| `frontend/` | Antigravity |
| `docs/api-contracts.md`, `docs/game-rules.md`, `docs/architecture.md`, `AGENTS.md` | Claude Code (contract changes via `docs(contract)` commits) |
| `docs/testing.md` | CC (backend sections), AG (frontend/E2E sections) |
| `README.md` | CC with AG-owned frontend section |
| Root config (`.gitignore`, `.editorconfig`) | Claude Code; AG may append frontend entries |

- Do not use bare `git stash` (shared stash stack across worktrees). Prefer a WIP commit; if you must stash, use a uniquely tagged `git stash push -u -m "<tag>"` and restore by SHA.
- Before opening a PR: rebase on latest `main`, run the full verification for your lane, and list verification output in the PR.
- Never commit generated artifacts (`target/`, `node_modules/`, `dist/`, test reports).
- Lockfiles (`package-lock.json`) are committed; Maven wrapper is committed.

## 6. Testing requirements

A change is not done without tests proportional to its risk. Tests are written alongside logic.

Backend:
- Unit tests: scoring, turn rotation, state transitions, telephone allocation (including property/invariant tests), guess normalization, validators — no Spring context, no Docker.
- Authorization tests for every protected endpoint/command (unauthenticated, wrong role, wrong room).
- REST integration tests (`@SpringBootTest` + MockMvc/WebTestClient + Testcontainers PostgreSQL).
- WebSocket integration tests (real STOMP client): auth, subscribe authorization, private-vs-public secrecy, reconnect snapshot.
- Concurrency tests for room join/capacity and timer/action races (manual `Clock`).
- Naming: `*Test` (unit), `*IT` (integration; failsafe). Tests reference rule IDs where applicable.

Frontend:
- Component tests (Vitest + Testing Library), canvas utility tests (coordinate transform, DPR, batching), store tests, API/WebSocket handler tests (mock transport).
- Playwright E2E for the scenarios in `MASTER_PROMPT` §15: multi-browser create/join, start, draw & guess, finish + rematch, telephone album, refresh/reconnect, disconnected host, unauthorized action rejection. Multi-user = multiple browser contexts.
- Accessibility smoke (axe) in E2E from Phase 7.

Rules: do not weaken or delete a failing test to get green; fix the cause. Flaky tests are bugs. Report test results honestly, including skips and failures.

## 7. Verification before reporting done

Run the lane's commands from `docs/implementation-plan.md` ("Verification commands") and report outcome. For UI changes, verify in a real browser (or state clearly that you could not). At the end of each phase: tests/builds, fix, verify flows, update docs, summarize, list known limitations.

## 8. Task lanes

- **Claude Code:** backend, architecture, contracts, tests (backend), security, Docker/CI/deployment, review of contract conformity.
- **Antigravity:** frontend, UI/UX and design system, accessibility, canvas client, browser/E2E testing, visual QA.
- Handoff protocol: CC publishes the contract and a running backend slice (or fixtures); AG implements against it and reports contract gaps; CC resolves them in a `docs(contract)` commit. Disputes escalate to the lead/user rather than silently diverging.

## 9. Security checklist (every PR)

- No client-supplied identity, role, score, timer or phase is trusted.
- Secrets never reach non-authorized clients (word, telephone sources).
- Inputs validated and size-limited; rate limits applied to new high-frequency commands.
- No tokens in URLs/logs. CORS/Origin allowlists env-driven.
- New endpoints/commands have authorization tests.
