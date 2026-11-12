---
title: Build: outbox, notifications and sweep (E-4)
type: task
status: closed
blocked_by: [10-build-booking-path]
assignee: Camilo Parra
---

## Question

Outbox relay with SKIP LOCKED driven by the 2 s drain loop and the Tick; per-consumer inbox; Notifications module (Recipients, Messages, Reminders at -24 h, delivery with 3 s timeout and jittered backoff up to 2 h); `cmd/fakeprovider` (switchable, lists received Messages); `cmd/ticker`; `POST /internal/tick` running Sweep -> drain -> deliver -> heartbeat; `/health` degraded after 15 min. Done when the E-4 test (clock +16 min, sweep twice -> one No-show, one Message) passes.

## Resolution

2026-11-11. Built the relay (`FOR UPDATE SKIP LOCKED`, 2 s drain loop plus the Tick), the per-consumer inboxes, Notifications (Confirmation, Reminder 24 h ahead, cancellation and no-show messages, 3 s timeout, exponential backoff with jitter, give up after 2 h), the Sweep with `sweep_state` and the reverse heartbeat on `/health`. Verified: a confirmation reaches the fake provider about 2 s after booking. With the provider off the booking still returns 201 and the message retries until the provider is back. `TestE4NoShowSweepRunsOnce` gives 1 no-show then 0, with exactly one message. Moving the clock +16 min turns health `degraded`.
