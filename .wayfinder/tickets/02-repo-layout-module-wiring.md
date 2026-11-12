---
title: Repo layout and module wiring
type: grilling
status: closed
blocked_by: [01-glossary-english-naming]
assignee: Camilo Parra
---

## Question

Fix the Go package layout so Go itself enforces the boundaries: `cmd/<app>`, per-module `internal/` with four layers, the public `api.go` with `New(conn, clock, dispatcher)`, where shared kernel (clock, event dispatcher, ids) lives without becoming a back door, how the embedded frontend is served, and the Makefile targets (`make check`).

## Resolution

Decided by the agent under delegation. The user said on 2026-11-11 to proceed with minimal input from them ("we just need to respect the artifact"), so the report was treated as binding and every gap was filled in the direction the report points. The user can veto any line.

- Layout: modules sit at the repo root (`scheduling/`, `clinical/`, `notifications/`, `identity/`, `audit/`), as the report has it (`agenda/internal/`). Each module has `api.go` (package `<module>`, the only public surface) plus `internal/{domain,application,infrastructure,presentation}`.
- Layer rule (ADR-001): presentation -> application -> domain; infrastructure -> domain. Ports (repository interfaces) live in `domain`; `api.go` is the only file that imports infrastructure and wires it in.
- `api.go` exposes `New(Deps) *Module`. `Deps` = {own pgx pool, `clock.Clock`, `*events.Bus`, plus the public APIs of other modules it needs, such as `identity.Verifier` and `audit.Recorder`}. That is the report's `New(conn, clock, dispatcher)` with explicit collaborators. `Module.Routes(mux)` registers HTTP handlers.
- Shared kernel: `internal/platform/{clock,events,httpx}` holds no domain types and no SQL. A fitness test fails if `platform` imports any module.
- Binaries: `cmd/clinic` (app), `cmd/migrate` (one-shot schema/role/seed as the DB owner, so the app never holds owner credentials), `cmd/fakeprovider`, `cmd/ticker`. One Dockerfile builds all four.
- Frontend: `web/` embedded with `go:embed`, served at `/` by `cmd/clinic`.
- `Makefile`: `check` = `go vet` + `go build` + `go test ./...` (integration tests need `DATABASE_URL`, otherwise they skip); plus `up`/`down`. On this machine use `mingw32-make`.
