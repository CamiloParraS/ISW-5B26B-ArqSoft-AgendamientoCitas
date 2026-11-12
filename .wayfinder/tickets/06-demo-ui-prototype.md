---
title: Demo UI shape (prototype)
type: prototype
status: closed
blocked_by: [01-glossary-english-naming]
assignee: Camilo Parra
---

## Question

What does the vanilla HTML/JS frontend look like so it best showcases the report? Prototype it cheaply and react to it: patient booking view, doctor clinical view (200 vs 403), admin/DPO audit-log view, and a demo panel that maps onto the 7-step script (fire 50 concurrent bookings and show 1 vs 49, show messages received by the fake provider, toggle provider, advance clock, trigger tick, health including the reverse-heartbeat status: last sweep age, degraded after 15 min). Output: a rough clickable prototype linked from the ticket, and the screen list.

## Resolution

Decided by the agent under delegation. The user said on 2026-11-11 to proceed with minimal input from them ("we just need to respect the artifact"), so the report was treated as binding and every gap was filled in the direction the report points. The user can veto any line. The user waived reacting to a throwaway prototype, so the screen list below is the spec, and the real UI ticket builds it directly.

- One page, `web/index.html` + `app.js` + `styles.css`, no build step. A top bar always shows: health pill (ok / degraded / down, with last sweep age), demo clock with +16 min / +24 h / reset, a **Tick now** button, a fake provider on/off switch, and a "signed in as" switcher across the seeded users.
- Screens, which follow the report's demo script:
  1. **Health**: live /health with DB status and heartbeat.
  2. **Concurrency (E-1)**: pick a slot, fire 50 bookings at once. A 50-cell grid ends with 1 green and 49 "slot taken" cells, plus alternatives and p95 latency.
  3. **Messages**: the fake provider's inbox, live.
  4. **Provider down (T12/T13)**: switch the provider off and book. The Message stays pending with attempts and next retry; switch it back on and it goes out.
  5. **Clinical access (E-2)**: Dr. Jeferson vs Dr. Camilo read Ana's record (200 vs 403), and the DPO sees both Access entries.
  6. **Crash (E-3)**: crash the app and time the outage until /health returns; the appointment survives.
  7. **No-show sweep (E-4)**: book a slot that starts soon, advance the clock, Tick twice: 1 No-show, 1 Message.
  8. **Fitness functions**: `make check` and the deliberate forbidden import, shown as commands to run, since a browser can't run `go test`.
- Plus a plain **Patient** view (open slots, book, my appointments, cancel or reschedule, with the 24 h rule enforced) so the app still looks like an app.
- Demo control surface (graduated from fog): demo-only endpoints under `/demo/*`, registered only when `DEMO_MODE=true`: `/demo/clock`, `/demo/provider` (proxied to the fake provider so everything stays on one origin), `/demo/burst` (50 goroutines against Scheduling's public API with 50 distinct patients, because a browser caps parallel connections at 6 per host and would not truly fire 50 at once), `/demo/crash` (`os.Exit(1)`, which compose's `restart: unless-stopped` and the healthcheck recover from; that stands in for E-3's unhandled error).
