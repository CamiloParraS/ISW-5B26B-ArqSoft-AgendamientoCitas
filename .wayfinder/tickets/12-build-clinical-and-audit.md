---
title: Build: clinical access and audit (E-2)
type: task
status: closed
blocked_by: [11-build-outbox-notifications-sweep]
assignee: Camilo Parra
---

## Question

Clinical module: Treatment relationships fed by AppointmentBooked/AppointmentCanceled, Encounters with AES-GCM column encryption, `GET /api/clinical/patients/{id}/encounters` authorized by role + relationship, 403 that reveals nothing; Audit module with append-only Access log written before every clinical read (fail closed), `GET /api/audit/access-entries` for the DPO. Done when the three-token E-2 test passes and the DB-role fitness test (`scheduling_app` cannot SELECT clinical tables) passes.

## Resolution

2026-11-11. Built Clinical (encounters with AES-256-GCM columns, a treatment relationship fed by Booked/Canceled events, `Decide` in the domain, audit before read and fail closed) and Audit (append-only `access_log`, DPO-only listing). Verified: Ana's record returns 200 for Dr. Jeferson, 403 for Dr. Camilo and Staff, and 403 for a malformed id. Every attempt is logged. `TestE2ClinicalAccess` and `TestRelationshipFollowsEvents` pass, the latter covering booking granting access and cancellation removing it.
