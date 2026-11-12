---
title: Write amendments back into the report
type: task
status: closed
blocked_by: [03-events-and-outbox-contract, 04-schema-and-db-roles]
assignee: Camilo Parra
---

## Question

Once the event/outbox contract and the schema have fixed the details, add the four adopted amendments (see "Architecture amendments to the report") to the Claude Doc "Informe de arquitectura - Agenda de citas medicas", in Spanish, as suggestions the team can review. The places they touch: section 6 (treatment-relationship latency, audit wording), section 7 (T4/T8 rows, or a new tactic row), section 10 (RS-2 "what to verify" becomes built), and section 11 (the end-to-end walk and the demo script). Done when the suggestions are in the doc and the ticket links them.

## Resolution

2026-11-11. Eight suggestion cards (Spanish) are open in the report (rev 249, https://claude.ai/artifact/M3wbEvSz3JL5wpcMbS8ikm). Anyone with edit access accepts or rejects each one on its card:

| Card | Where | Amendment |
|------|-------|-----------|
| sg_akooypmbrs | Section 6, Clinical and Audit row: "Cómo quedó" | Audit before read, fail closed (no shared transaction) |
| sg_6vn65fydjb | Section 7, T4 "Cómo se aplica" | Continuous drain every 2 s; the tick is the catch-up |
| sg_fhcac8xy7o | Section 7, T4 "Cómo se aplica" | Idempotent inbox per consumer |
| sg_j5ozg1a9ld | Section 7, T8 "Cómo se aplica" | Entry written before data is returned, denials included |
| sg_ay9c20qi0j | Section 10, RS-2 "Qué verificar" | Reverse heartbeat is built: /salud is degraded after 15 min |
| sg_nhxdkhs4hn | Section 11 walk, step 2 | The drain loop has already sent it; the tick catches up |
| sg_143jvoc0hz | Section 11 walk, step 5 | Audit records before responding |
| sg_ad19pvi1uf | Section 11 demo script, step 1 | Demo console and heartbeat age |

Follow-up (rev 256): three more cards for places where the build differs from the report's text:

| Card | Where | Change |
|------|-------|--------|
| sg_wpsvdcffv0 | Section 11 demo script, step 6 | Exit with an error instead of `docker compose kill`, which doesn't trigger a restart |
| sg_ti3uaudkxu | Section 11 demo script, step 4 | The message that waits and retries is the confirmation, not the reminder |
| sg_qb9cm1vuwn | Section 11, under "Vista de desarrollo" | English names in the implementation (GLOSSARY.md), where the tests live, Ticker every 30 s |
