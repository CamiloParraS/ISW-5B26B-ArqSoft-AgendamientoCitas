---
title: Build: foundation
type: task
status: closed
blocked_by: []
assignee: Camilo Parra
---

## Question

Lay the ground every slice stands on: `go.mod` (module `clinic`), `internal/platform` (clock with demo offset, events bus, http helpers), `cmd/migrate` with the five schemas, `<module>_app` roles, grants and seed data, the Identity module (PBKDF2 credentials, HMAC access tokens, `POST /api/identity/tokens`), `cmd/clinic` with `/health`, the Dockerfile, `docker-compose.yml` (postgres, migrate, app, ticker, fakeprovider) and the Makefile. Done when `docker compose up` serves `/health` and a seeded user can get a token.

## Resolution

2026-11-11. Built: `go.mod` (module `clinic`, pgx v5.13), `internal/platform/{clock,events,httpx,ids}`, `internal/migrations` (embedded SQL, `EnsureRoles`, `ModuleDSN`), `internal/seed`, the Identity module, `cmd/{clinic,migrate,ticker,fakeprovider}`, Dockerfile, `docker-compose.yml`, Makefile. Verified: `docker compose up --build` comes up healthy, migrate applies `0001_schemas.sql` and seeds, `/health` reports `ok` for all five roles, and every seeded user gets a token.
