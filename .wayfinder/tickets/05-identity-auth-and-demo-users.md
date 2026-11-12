---
title: Identity, tokens and seeded demo users
type: grilling
status: closed
blocked_by: [01-glossary-english-naming]
assignee: Camilo Parra
---

## Question

How Identity issues and other modules verify signed tokens (T5), the role model (patient, doctor, admin, data-protection officer), the internal-tick token, and the seed data the demo needs (doctors, patients incl. one doctor with and one without treatment relationship, open slots at the report's Monday 8:00 cardiology scenario).

## Resolution

Decided by the agent under delegation. The user said on 2026-11-11 to proceed with minimal input from them ("we just need to respect the artifact"), so the report was treated as binding and every gap was filled in the direction the report points. The user can veto any line.

- Access token: HMAC-SHA256 JWT built with the standard library, claims `{sub, role, subject_id, name, exp}`, 8 h expiry, secret `TOKEN_SECRET`. Issued by `POST /api/identity/tokens {username,password}`. Other modules get `identity.Verifier` through `Deps` and only verify (T5).
- Tick token: shared secret `TICK_TOKEN`, header `X-Tick-Token`.
- Seed (based on the report's scenarios; every password is `demo`): Dr. Jeferson Morillo (Cardiology, `jeferson`), Dr. Camilo Parra (Orthopedics, `camilo`), Dr. Juan Diego Lopez (General medicine, `juandiego`); patient Ana Gomez (`ana`), who has a past attended appointment and an Encounter with Dr. Jeferson, so E-2 has a 200 case; Staff `staff`; DPO `dpo`; 50 burst patients (`patient01`..`patient50`) for E-1. Slots: each doctor, next Monday 07:00-12:00 every 20 min (America/Bogota), including the report's Monday 8:00 Cardiology slot.
