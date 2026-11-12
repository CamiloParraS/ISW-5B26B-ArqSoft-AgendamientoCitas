---
title: Event and outbox contract
type: grilling
status: closed
blocked_by: [01-glossary-english-naming]
assignee: Camilo Parra
---

## Question

Define the events (AppointmentBooked, AppointmentCanceled, no-show, reminder), their payloads (no clinical data), the outbox table shape, how the dispatcher claims and processes rows on `/internal/tick`, delivery semantics (at-least-once plus idempotent consumers), and the retry/backoff schedule for the messaging provider (T4, T12, T13). Per the architecture amendments, it also defines: the in-process drain loop (its interval, and how it and the tick claim rows without double-processing, e.g. `FOR UPDATE SKIP LOCKED`), the per-consumer inbox that de-duplicates events, and where the last-sweep timestamp for the reverse heartbeat is written.

## Resolution

Decided by the agent under delegation. The user said on 2026-11-11 to proceed with minimal input from them ("we just need to respect the artifact"), so the report was treated as binding and every gap was filled in the direction the report points. The user can veto any line.

- Envelope (`platform/events`): `{id uuid, type, occurred_at, payload json}`. Payloads carry ids, names and times only, never clinical data.
- Events (all from Scheduling): `AppointmentBooked{appointment_id, slot_id, patient_id, patient_name, doctor_id, doctor_name, specialty, starts_at, rescheduled_from?}`, `AppointmentCanceled{appointment_id, patient_id, doctor_id, starts_at, reason: canceled|rescheduled}`, `NoShowRecorded{appointment_id, patient_id, doctor_id, starts_at}`. There is no "reminder" event: Notifications schedules Reminders itself from `AppointmentBooked`.
- Outbox: `scheduling.outbox(id bigserial, event_id, type, payload jsonb, occurred_at, delivered_at, attempts, next_attempt_at, last_error)`, written in the same transaction as the state change (T4).
- Relay: `Module.DrainOutbox(ctx)` claims due rows with `FOR UPDATE SKIP LOCKED` (batch of 100), publishes each to the in-process `events.Bus` synchronously, marks it delivered if every handler succeeds, and otherwise bumps `attempts` and `next_attempt_at` (exponential, capped at 5 min). It runs from the drain loop (every 2 s, amendment 1) and from every Tick, which is the catch-up after a suspension. SKIP LOCKED keeps the loop and the Tick from double-claiming.
- Delivery is at-least-once. Each consumer has its own `<schema>.inbox(event_id pk, processed_at)` and inserts into it in the same transaction as its effect. A duplicate becomes a no-op (amendment 4).
- Messages (T12, T13): Notifications stores Messages (`confirmation`, `reminder` at starts_at-24h, `no_show`, `cancellation`) with `send_at`. Pending Reminders are canceled on `AppointmentCanceled`. Delivery runs on the drain loop and on every Tick: 3 s timeout per call, retry at 1, 2, 4, 8... min with ±20% jitter, giving up after 2 h (status `failed`). A provider failure never touches the booking.
- Tick (`POST /internal/tick`, header `X-Tick-Token`): Scheduling Sweep (no-shows via conditional update `WHERE status='confirmed' AND starts_at + 15m <= $now`), then drain outbox, then deliver due Messages, then write `scheduling.sweep_state.last_swept_at`. Running it twice duplicates nothing (T14).
- Reverse heartbeat (amendment 2): `/health` reports `last_sweep_age` and returns `degraded` when it exceeds 15 min on the injected clock.
- Added glossary term: **Confirmation** (the Message sent on booking), which demo step 3 needs.
