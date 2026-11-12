# Clinic scheduling: walking skeleton

The implementation of the architecture report *Informe de arquitectura · Agenda de citas médicas*:
a Go modular monolith (Identity, Scheduling, Clinical, Notifications, Audit) on PostgreSQL, a
Ticker that wakes it up, and a fake messaging provider. The report is the source of truth;
[`.wayfinder/map.md`](.wayfinder/map.md) records the decisions made while building it, and
[`GLOSSARY.md`](GLOSSARY.md) holds the vocabulary used in the code.

## Run it

```sh
docker compose up --build        # or: make up
```

Open **http://localhost:8080**. The first start migrates and seeds the database. Postgres is
published on `localhost:55432` because 5432 is often taken by a local install.

| Container      | What it is                                                                 |
|----------------|----------------------------------------------------------------------------|
| `app`          | The monolith and the demo console (port 8080). Restarts itself when it dies. |
| `postgres`     | One database, one schema per module, one login role per module.            |
| `ticker`       | Calls `POST /internal/tick` every 30 s (the report uses 5 min).            |
| `fakeprovider` | Stands in for the SMS/WhatsApp provider. You can switch it off (port 8081). |
| `migrate`      | One-shot: creates roles, applies migrations, seeds. The only holder of owner credentials. |

Every demo user's password is `demo`: `ana` and `luis` (patients), `jeferson`, `camilo` and
`juandiego` (doctors), `staff`, `dpo`.

## The demo console

The left column follows the report's live demo script. The top bar is always available: the
health pill, the **demo clock** (+16 min, +24 h, reset), **Tick now**, the provider switch and
**Signed in as**.

1. **Health check.** Per-role database pings and the reverse heartbeat. Move the clock +16 min and the status turns *degraded*.
2. **50 bookings, 1 slot (E-1).** 50 goroutines book the same slot: 1 confirmed, 49 *slot taken* with 3 alternatives, and the p95.
3. **Messages delivered.** The outbox relay → Notifications → the fake provider's inbox.
4. **Provider down.** The booking is still confirmed, and the message retries with backoff until the provider is back.
5. **Clinical access (E-2).** Dr. Jeferson gets 200, Dr. Camilo and Staff get 403. Everything is in the access log (shown as the DPO). Booking Ana with Dr. Camilo grants access a moment later.
6. **Crash and recover (E-3).** The process exits with an error, Docker restarts it, and no appointments are lost.
7. **No-show sweep (E-4).** Open a slot, book it, move the clock 21 min and tick twice: one no-show, one message.
8. **Fitness functions.** What `make check` enforces.

**Patient view** and **Doctor view** are the app itself: book, cancel, reschedule (24 h rule),
check in, read and write encounters.

`DEMO_MODE=true` enables `/demo/*` (clock, tick, burst, crash, provider switch) and the user
list. Without it those routes don't exist and the clock is the system clock.

## Fitness functions

```sh
docker compose up -d postgres
make check            # Windows: mingw32-make check
```

This runs `go vet`, `go build` and every test against a separate `clinic_test` database.

| Rule | Checked by |
|------|------------|
| A module uses another only through its `api.go` | the Go compiler (`internal/` packages) |
| No import cycles | the Go compiler |
| presentation → application → domain, infrastructure → domain; domain has no `net/http` or `pgx` | `internal/archtest` |
| A module's DB role can't read another schema; the audit log is append-only; encounters can't be deleted | `TestDBRoleBoundaries` |
| 50 concurrent bookings → exactly 1, ×100 | `TestE1ConcurrentBooking` |
| E-2, E-4, idempotency, the 24 h rule, relationships following events | `internal/app/scenarios_test.go` |

## Layout

```
cmd/clinic        the monolith         cmd/ticker        the Ticker
cmd/migrate       roles, schema, seed  cmd/fakeprovider  the messaging provider
<module>/api.go   a module's public interface (the only thing other modules import)
<module>/internal/{domain,application,infrastructure,presentation}
internal/platform shared kernel: clock, events, httpx, ids (imports no module)
internal/app      wiring, Tick, /health, /demo
web/              the console (vanilla HTML/JS, embedded in the binary)
```

## Changes to the report

Four amendments, agreed before building (ticket 08 writes them back into the report):

1. **Continuous outbox drain.** The relay runs every 2 s while the process is awake, not only on a Tick, so confirmations go out in seconds.
2. **Reverse heartbeat.** `/health` turns *degraded* when no Sweep has run for 15 min, so a dead Ticker is visible.
3. **Audit before read, fail closed.** The access entry is written before clinical data is returned. If it can't be written, the read fails.
4. **Idempotent consumer inbox.** Each consumer records the event ids it has processed, so an at-least-once relay never applies an event twice.
