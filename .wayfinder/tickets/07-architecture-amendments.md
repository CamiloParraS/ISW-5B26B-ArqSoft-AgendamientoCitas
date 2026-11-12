---
title: Architecture amendments to the report
type: grilling
status: closed
blocked_by: []
assignee: Camilo Parra
---

## Question

The earlier charting session recorded "keep the architecture as written; only add the frontend". On 2026-11-11 the user asked instead for changes "as you see fit". Which amendments to the report's architecture does the build adopt? The modular monolith, ADR-001..003, scenarios E-1..E-4 and tactics T1..T14 stay as fixed inputs.

## Resolution

2026-11-11, decided by the user. Scope moves from "as written" to "as written plus these four amendments". Every amendment closes a gap where the report's own text promises something its design does not deliver. All four are adopted:

1. **Continuous outbox drain.** While the process is awake, a background loop sends the outbox about every 2 s. `POST /internal/tick` is still the guaranteed catch-up after a suspension. Reason: section 6 promises the treatment relationship within "seconds", but a tick-only drain gives up to 5 min. It also makes demo step 3 instant.
2. **Reverse heartbeat (RS-2).** The app stores the time of the last sweep. `/health` reports its age and returns `degraded` when it is older than 15 min, and the UI shows the same status. Reason: RS-2 says this alert "must be built", but nothing in the design builds it.
3. **Audit before read, fail closed.** Clinical writes the access entry through Audit's public API before it returns any data. If that write fails, the read fails. Denials are audited the same way. Reason: each module has its own DB role and connection, so Clinical and Audit cannot share a transaction, which section 6 implicitly assumes.
4. **Idempotent consumer inbox.** Each consumer module (Notifications, Clinical) keeps a processed-event-id table in its own schema and skips events it has already handled. Reason: delivery is at-least-once (T4 plus the drain loop plus the tick can overlap), so without this a resend could send a second SMS or create a duplicate treatment relationship.

Consequences for other tickets: the event/outbox contract has to define the drain loop and the inbox. The schema has to add inbox tables and the sweep-state row. The demo UI has to show heartbeat status. The amendments are written back into the report once their details are fixed (see "Write amendments back into the report").
