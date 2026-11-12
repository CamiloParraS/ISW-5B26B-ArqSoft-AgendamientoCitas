---
title: Build: booking path (E-1)
type: task
status: closed
blocked_by: [09-build-foundation]
assignee: Camilo Parra
---

## Question

Scheduling module in four layers: list open Slots, Book (T1 conditional write + T2 partial unique index + T3 Idempotency-Key + T4 outbox row, all in one transaction, with 409 `slot_taken` and 3 alternatives), Cancellation with the 24 h rule, Reschedule (cancel + book in one transaction), Check in, the Doctor's schedule, Staff opening Slots. Done when the E-1 test (50 goroutines on one Slot -> exactly 1 Appointment) passes against Postgres.

## Resolution

2026-11-11. Built Scheduling end to end: conditional `UPDATE ... WHERE status = 'open'` (T1), partial unique index `appointments_one_live_per_slot` (T2), idempotency keys with replay (T3), outbox row written in the booking transaction (T4), cancel/reschedule with the 24 h rule, check-in, and 409 `slot_taken` with three alternatives. Verified: `/demo/burst` gives 1 confirmed, 49 slot_taken, p95 of about 65 ms. `TestE1ConcurrentBooking` passes 100 runs × 50 goroutines. `TestBookingIsIdempotent` and `TestCancellationNeeds24Hours` pass.
