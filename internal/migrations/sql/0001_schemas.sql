-- One schema per module; each runtime role sees only its own schema (T7).
-- Roles are created by migrations.EnsureRoles before this file runs.

REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS scheduling;
CREATE SCHEMA IF NOT EXISTS clinical;
CREATE SCHEMA IF NOT EXISTS notifications;
CREATE SCHEMA IF NOT EXISTS audit;

REVOKE ALL ON SCHEMA identity, scheduling, clinical, notifications, audit FROM PUBLIC;

GRANT USAGE ON SCHEMA identity      TO identity_app;
GRANT USAGE ON SCHEMA scheduling    TO scheduling_app;
GRANT USAGE ON SCHEMA clinical      TO clinical_app;
GRANT USAGE ON SCHEMA notifications TO notifications_app;
GRANT USAGE ON SCHEMA audit         TO audit_app;

-- ---------------------------------------------------------------- identity
CREATE TABLE identity.users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username      text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role          text NOT NULL CHECK (role IN ('patient','doctor','staff','dpo')),
    subject_id    uuid,
    display_name  text NOT NULL
);
GRANT SELECT ON identity.users TO identity_app;

-- -------------------------------------------------------------- scheduling
CREATE TABLE scheduling.doctors (
    id        uuid PRIMARY KEY,
    name      text NOT NULL,
    specialty text NOT NULL
);

CREATE TABLE scheduling.patients (
    id       uuid PRIMARY KEY,
    name     text NOT NULL,
    document text NOT NULL UNIQUE,
    phone    text NOT NULL
);

CREATE TABLE scheduling.slots (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id uuid NOT NULL REFERENCES scheduling.doctors(id),
    starts_at timestamptz NOT NULL,
    status    text NOT NULL DEFAULT 'open' CHECK (status IN ('open','booked')),
    UNIQUE (doctor_id, starts_at)
);
CREATE INDEX slots_open_idx ON scheduling.slots (doctor_id, starts_at) WHERE status = 'open';

CREATE TABLE scheduling.appointments (
    id               uuid PRIMARY KEY,
    slot_id          uuid NOT NULL REFERENCES scheduling.slots(id),
    patient_id       uuid NOT NULL REFERENCES scheduling.patients(id),
    doctor_id        uuid NOT NULL REFERENCES scheduling.doctors(id),
    starts_at        timestamptz NOT NULL,
    status           text NOT NULL CHECK (status IN ('confirmed','canceled','attended','no_show')),
    rescheduled_from uuid REFERENCES scheduling.appointments(id),
    checked_in_at    timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);
-- T2: the safety net. At most one live (non-canceled) Appointment per Slot,
-- even if some code path skips the conditional write (T1).
CREATE UNIQUE INDEX appointments_one_live_per_slot
    ON scheduling.appointments (slot_id) WHERE status <> 'canceled';
CREATE INDEX appointments_overdue_idx ON scheduling.appointments (starts_at) WHERE status = 'confirmed';
CREATE INDEX appointments_patient_idx ON scheduling.appointments (patient_id);
CREATE INDEX appointments_doctor_idx ON scheduling.appointments (doctor_id);

-- T3: a retried booking returns the Appointment the first attempt created.
CREATE TABLE scheduling.idempotency_keys (
    patient_id     uuid NOT NULL,
    key            text NOT NULL,
    appointment_id uuid NOT NULL REFERENCES scheduling.appointments(id),
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (patient_id, key)
);

-- T4: transactional outbox.
CREATE TABLE scheduling.outbox (
    id              bigserial PRIMARY KEY,
    event_id        uuid NOT NULL UNIQUE,
    type            text NOT NULL,
    payload         jsonb NOT NULL,
    occurred_at     timestamptz NOT NULL,
    delivered_at    timestamptz,
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error      text
);
CREATE INDEX outbox_pending_idx ON scheduling.outbox (next_attempt_at) WHERE delivered_at IS NULL;

-- Reverse heartbeat (amendment 2): when did the last Sweep run?
CREATE TABLE scheduling.sweep_state (
    id             int PRIMARY KEY CHECK (id = 1),
    last_swept_at  timestamptz NOT NULL,
    no_shows_total bigint NOT NULL DEFAULT 0
);

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA scheduling TO scheduling_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA scheduling TO scheduling_app;

-- ---------------------------------------------------------------- clinical
CREATE TABLE clinical.treatment_relationships (
    appointment_id uuid PRIMARY KEY,
    doctor_id      uuid NOT NULL,
    patient_id     uuid NOT NULL,
    created_at     timestamptz NOT NULL
);
CREATE INDEX treatment_rel_pair_idx ON clinical.treatment_relationships (doctor_id, patient_id);

-- T9: Diagnosis and Prescription are encrypted at the column level (AES-256-GCM).
-- R-3: never deleted; corrections are new versions that supersede the old one.
CREATE TABLE clinical.encounters (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       uuid NOT NULL,
    doctor_id        uuid NOT NULL,
    doctor_name      text NOT NULL,
    occurred_at      timestamptz NOT NULL,
    diagnosis_enc    bytea NOT NULL,
    prescription_enc bytea NOT NULL,
    version          int NOT NULL DEFAULT 1,
    supersedes       uuid REFERENCES clinical.encounters(id)
);
CREATE INDEX encounters_patient_idx ON clinical.encounters (patient_id, occurred_at DESC);

CREATE TABLE clinical.inbox (
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL
);

GRANT SELECT, INSERT, DELETE ON clinical.treatment_relationships TO clinical_app;
GRANT SELECT, INSERT ON clinical.encounters TO clinical_app;
GRANT SELECT, INSERT ON clinical.inbox TO clinical_app;

-- ----------------------------------------------------------- notifications
CREATE TABLE notifications.recipients (
    patient_id uuid PRIMARY KEY,
    name       text NOT NULL,
    channel    text NOT NULL CHECK (channel IN ('sms','email')),
    address    text NOT NULL
);

CREATE TABLE notifications.messages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id  uuid NOT NULL,
    patient_id      uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('confirmation','reminder','no_show','cancellation')),
    channel         text NOT NULL,
    address         text NOT NULL,
    body            text NOT NULL,
    send_at         timestamptz NOT NULL,
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','sent','canceled','failed')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error      text,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL,
    UNIQUE (appointment_id, kind)
);
CREATE INDEX messages_due_idx ON notifications.messages (next_attempt_at) WHERE status = 'pending';

CREATE TABLE notifications.delivery_attempts (
    id         bigserial PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES notifications.messages(id),
    at         timestamptz NOT NULL,
    ok         boolean NOT NULL,
    error      text
);

CREATE TABLE notifications.inbox (
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA notifications TO notifications_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA notifications TO notifications_app;

-- ------------------------------------------------------------------- audit
-- T8: append-only. audit_app can INSERT and SELECT, never UPDATE or DELETE.
CREATE TABLE audit.access_log (
    id         bigserial PRIMARY KEY,
    at         timestamptz NOT NULL,
    user_id    text NOT NULL,
    user_name  text NOT NULL,
    role       text NOT NULL,
    action     text NOT NULL,
    resource   text NOT NULL,
    patient_id text,
    allowed    boolean NOT NULL,
    reason     text NOT NULL,
    source     text NOT NULL
);
CREATE INDEX access_log_at_idx ON audit.access_log (at DESC);

GRANT SELECT, INSERT ON audit.access_log TO audit_app;
GRANT USAGE ON SEQUENCE audit.access_log_id_seq TO audit_app;
