---
title: Build: fitness functions and make check
type: task
status: closed
blocked_by: [12-build-clinical-and-audit]
assignee: Camilo Parra
---

## Question

The layer test that reads imports with `go list` and fails on a forbidden edge (presentation->infrastructure, domain->net/http or pgx, platform->any module), plus `make check` running vet, build and every test. Done when `make check` is green and adding a forbidden import makes it red.

## Resolution

2026-11-11. Built `internal/archtest` (layer rules read from `go list`) and the DB-role test, wired into `make check` (vet, build, then `go test -p 1` on the separate `clinic_test` database). The compiler covers the other two rules (`internal/` packages, no cycles). Verified: `mingw32-make check` passes. Adding `import _ "github.com/jackc/pgx/v5"` to `scheduling/internal/domain` makes it fail with `clinic/scheduling/internal/domain must not import github.com/jackc/pgx/v5`. `TestDBRoleBoundaries` checks that 8 cross-schema or forbidden statements fail with 42501.
