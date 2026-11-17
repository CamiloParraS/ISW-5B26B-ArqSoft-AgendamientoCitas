-- Contexto Agenda (núcleo): pacientes, cupos, citas, idempotencia, políticas y outbox
CREATE SCHEMA IF NOT EXISTS agenda;

CREATE TABLE agenda.paciente (
    id        uuid PRIMARY KEY,                 -- = sub del token
    documento text NOT NULL UNIQUE,
    nombre    text NOT NULL,
    telefono  text
);

CREATE TABLE agenda.cupo (
    id             uuid PRIMARY KEY,
    medico_id      uuid NOT NULL,
    sede_id        uuid NOT NULL,
    inicio         timestamptz NOT NULL,
    fin            timestamptz NOT NULL,
    estado         text NOT NULL CHECK (estado IN ('LIBRE', 'RETENIDO', 'RESERVADO', 'BLOQUEADO')),
    retenido_por   uuid,
    retenido_hasta timestamptz,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT cupo_medico_inicio_uk UNIQUE (medico_id, inicio)   -- la BD arbitra la carrera
);

CREATE INDEX cupo_retenidos_idx ON agenda.cupo (retenido_hasta) WHERE estado = 'RETENIDO';
CREATE INDEX cupo_libres_idx ON agenda.cupo (medico_id, inicio) WHERE estado = 'LIBRE';

CREATE TABLE agenda.cita (
    id            uuid PRIMARY KEY,
    cupo_id       uuid NOT NULL REFERENCES agenda.cupo (id),
    paciente_id   uuid NOT NULL REFERENCES agenda.paciente (id),
    medico_id     uuid NOT NULL,
    sede_id       uuid NOT NULL,
    inicio        timestamptz NOT NULL,
    fin           timestamptz NOT NULL,
    estado        text NOT NULL CHECK (estado IN ('CONFIRMADA', 'CANCELADA', 'ATENDIDA')),
    creada_en     timestamptz NOT NULL DEFAULT now(),
    cancelada_en  timestamptz,
    con_penalidad boolean NOT NULL DEFAULT false,
    atendida_en   timestamptz
);

-- Un cupo pertenece a una sola cita viva: nunca se asigna dos veces
CREATE UNIQUE INDEX cita_cupo_viva_uk ON agenda.cita (cupo_id) WHERE estado <> 'CANCELADA';
CREATE INDEX cita_paciente_idx ON agenda.cita (paciente_id, inicio);
CREATE INDEX cita_medico_idx ON agenda.cita (medico_id, inicio);

CREATE TABLE agenda.clave_idempotencia (
    clave       text PRIMARY KEY,
    paciente_id uuid NOT NULL,
    operacion   text NOT NULL,
    cita_id     uuid NOT NULL,
    creada_en   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agenda.politica_cancelacion (
    sede_id        uuid PRIMARY KEY,
    horas_minimas  int NOT NULL CHECK (horas_minimas >= 0),
    actualizada_en timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agenda.outbox (
    id             uuid PRIMARY KEY,
    tipo           text NOT NULL,
    routing_key    text NOT NULL,
    carga          jsonb NOT NULL,
    correlacion_id text,
    creado_en      timestamptz NOT NULL DEFAULT now(),
    publicado_en   timestamptz,
    intentos       int NOT NULL DEFAULT 0
);

CREATE INDEX outbox_pendientes_idx ON agenda.outbox (creado_en) WHERE publicado_en IS NULL;
