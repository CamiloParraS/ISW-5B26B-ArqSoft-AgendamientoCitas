---
label: wayfinder:map
title: Clinic appointment scheduling - walking skeleton and demo UI
---

## Destination

A working walking skeleton of the architecture report "Informe de arquitectura - Agenda de citas medicas" (modular monolith in Go, PostgreSQL, one schema and role per module, transactional outbox, external-scheduler sweep, fake messaging provider), started with `docker compose up`, plus a vanilla HTML/JS frontend served by the Go app that drives the report's 7-step live demo. Decisions are resolved as tickets first; the build follows them.

## Notes

- Source of truth: the report (Claude Doc "Informe de arquitectura - Agenda de citas medicas", https://claude.ai/artifact/M3wbEvSz3JL5wpcMbS8ikm). Scenarios E-1..E-4, tactics T1..T14, ADR-001..003 and the 5 fitness functions are fixed inputs.
- User choices: the report's architecture plus four targeted amendments (see Decisions so far; no trimming); frontend is vanilla HTML/JS served by the Go app (no build step); EVERYTHING in English (code identifiers, API paths, UI, docs), so the report's Spanish domain terms need a mapping (see glossary ticket).
- Override of "plan, don't do": execution of the skeleton and UI is in scope for this map. Planning tickets come first; the build is sliced after the frontier clears.
- Tracker: local markdown. Tickets are files in `.wayfinder/tickets/`; `status`, `type`, `blocked_by`, `assignee` live in front-matter. Frontier = open, unblocked, unassigned.

## Decisions so far

<!-- one line per closed ticket: [title](tickets/file.md): gist -->

- [Glossary and English naming](tickets/01-glossary-english-naming.md): English domain terms in GLOSSARY.md with Spanish report terms; modules `scheduling/clinical/notifications/identity/audit`, Go module `clinic`, external scheduler renamed Ticker, American spelling.
- [Repo layout and module wiring](tickets/02-repo-layout-module-wiring.md): modules at repo root with `api.go` + 4 internal layers, ports in domain, shared kernel `internal/platform`, four binaries incl. a one-shot `migrate`, embedded `web/`.
- [Event and outbox contract](tickets/03-events-and-outbox-contract.md): 3 Scheduling events, outbox relayed with SKIP LOCKED by a 2 s loop and the Tick, per-consumer inbox, Notifications owns Reminders and backoff, Tick = Sweep -> drain -> deliver -> heartbeat.
- [Database schema, roles and migrations](tickets/04-schema-and-db-roles.md): per-module tables and `<module>_app` roles, partial unique index, AES-GCM clinical columns, insert-only Access log, owner creds only in `migrate`, PG16 on host port 55432.
- [Identity, tokens and seeded demo users](tickets/05-identity-auth-and-demo-users.md): stdlib HMAC JWT, PBKDF2 passwords, `X-Tick-Token`, seeded doctors/patients/staff/DPO and 50 burst patients, Monday 07:00-12:00 slots.
- [Demo UI shape (prototype)](tickets/06-demo-ui-prototype.md): one-page console with a persistent control bar and 8 screens following the demo script plus a Patient view; `/demo/*` endpoints behind `DEMO_MODE`.
- [Architecture amendments to the report](tickets/07-architecture-amendments.md): as written plus 4 fixes: continuous outbox drain (tick stays as catch-up), reverse heartbeat on /health, audit before read and fail closed, an idempotent consumer inbox per module.
- [Write amendments back into the report](tickets/08-write-amendments-back-to-report.md): 8 Spanish suggestion cards in report sections 6, 7, 10 and 11, waiting for the team to accept them.
- [Build: foundation](tickets/09-build-foundation.md): compose stack, migrate/seed, Identity and /health running.
- [Build: booking path](tickets/10-build-booking-path.md): T1-T3 booking; E-1 holds over 100x50 runs, p95 about 65 ms.
- [Build: outbox, notifications and sweep](tickets/11-build-outbox-notifications-sweep.md): outbox relay, inboxes, retries with backoff, idempotent Sweep and heartbeat; E-4 test passes.
- [Build: clinical and audit](tickets/12-build-clinical-and-audit.md): encrypted encounters, event-fed relationships, audit before read; E-2 tests pass.
- [Build: fitness functions](tickets/13-build-fitness-functions.md): `make check` runs layer and DB-role tests; the compiler covers the other rules.
- [Build: demo UI](tickets/14-build-demo-ui.md): embedded vanilla console for the 8-step script plus Patient and Doctor views.

## Not yet specified

<!-- empty: every patch graduated into a decision or a build ticket. Build order: foundation -> booking path -> outbox/notifications/sweep -> clinical/audit -> fitness functions and demo UI; write-back to the report runs alongside. -->

## Out of scope

- Real hosting/deployment on a free tier and verifying risks RS-1..RS-5: they need a real provider and a real clinic.
- C4 diagrams as code (`docs/c4/`) and the ADR files in `docs/adr/`: documentation deliverables already covered by the report, not by this build.
- Sustentacion (defense) preparation material.
- External alerting on a stale heartbeat (paging, email to the clinic): needs real hosting; the skeleton surfaces it on /health and in the UI only.
