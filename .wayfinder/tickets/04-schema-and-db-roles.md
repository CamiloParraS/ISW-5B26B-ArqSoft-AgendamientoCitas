---
title: Database schema, roles and migrations
type: grilling
status: closed
blocked_by: [01-glossary-english-naming]
assignee: Camilo Parra
---

## Question

One PostgreSQL, one schema and one role per module. Define the tables (slots with partial unique index, appointments, idempotency keys, outbox, treatment relationships, clinical records with column-level encryption, append-only audit log with no UPDATE/DELETE grant, a processed-events inbox table per consumer module, the sweep-state row for the reverse heartbeat), grants per role, and how migrations and role creation run on `docker compose up` (T1, T2, T3, T7, T8, T9).

## Resolution

Decided by the agent under delegation. The user said on 2026-11-11 to proceed with minimal input from them ("we just need to respect the artifact"), so the report was treated as binding and every gap was filled in the direction the report points. The user can veto any line.

- `scheduling`: `doctors(id, name, specialty)`, `patients(id, name, document, phone)`, `slots(id, doctor_id, starts_at, status)`, `appointments(id, slot_id, patient_id, doctor_id, status, rescheduled_from, checked_in_at, created_at, updated_at)` with a partial unique index `ON appointments(slot_id) WHERE status <> 'canceled'` (T2), `idempotency_keys(patient_id, key, appointment_id, created_at, pk(patient_id,key))` (T3), `outbox`, `sweep_state`.
- `clinical`: `treatment_relationships(appointment_id pk, doctor_id, patient_id)`, `encounters(id, patient_id, doctor_id, occurred_at, diagnosis_enc bytea, prescription_enc bytea, version, supersedes)` with AES-256-GCM column encryption, key from `CLINICAL_KEY` (T9), `inbox`. No DELETE grant on encounters (R-3).
- `notifications`: `recipients(patient_id, name, channel, address)`, `messages(...)`, `delivery_attempts(...)`, `inbox`.
- `identity`: `users(id, username, password_hash, role, subject_id, display_name)`. Passwords use PBKDF2-SHA256 from the standard library.
- `audit`: `access_log(id, at, user_id, role, action, resource, patient_id, allowed, source)`. `audit_app` has only INSERT and SELECT (T8).
- Roles: `<module>_app` LOGIN, USAGE on its own schema, DML on its own tables and nothing else (T7). The owner role (the compose `POSTGRES_USER`) is used only by `cmd/migrate`.
- Migrations: ordered embedded `.sql` files tracked in `public.schema_migrations`. `cmd/migrate` creates or updates the roles with passwords from env, applies migrations, and seeds when `-seed` is passed. Compose runs `migrate` as a one-shot service, and `app` waits for `service_completed_successfully`. Postgres 16; host port 55432, because this machine already runs a local PG18 on 5432.
