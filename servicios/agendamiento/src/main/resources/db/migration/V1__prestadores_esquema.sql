-- Contexto Red de prestadores: sedes, especialidades, médicos y horarios
CREATE SCHEMA IF NOT EXISTS prestadores;

CREATE TABLE prestadores.sede (
    id        uuid PRIMARY KEY,
    nombre    text NOT NULL,
    direccion text NOT NULL
);

CREATE TABLE prestadores.especialidad (
    id     uuid PRIMARY KEY,
    nombre text NOT NULL UNIQUE
);

CREATE TABLE prestadores.medico (
    id              uuid PRIMARY KEY,
    nombre          text NOT NULL,
    especialidad_id uuid NOT NULL REFERENCES prestadores.especialidad (id),
    sede_id         uuid NOT NULL REFERENCES prestadores.sede (id)
);

CREATE TABLE prestadores.horario (
    id                uuid PRIMARY KEY,
    medico_id         uuid NOT NULL REFERENCES prestadores.medico (id),
    sede_id           uuid NOT NULL REFERENCES prestadores.sede (id),
    desde             date NOT NULL,
    hasta             date NOT NULL,
    dias_semana       text NOT NULL,            -- días ISO separados por coma: 1=lunes ... 7=domingo
    hora_inicio       time NOT NULL,
    hora_fin          time NOT NULL,
    duracion_minutos  int  NOT NULL CHECK (duracion_minutos > 0),
    publicado_en      timestamptz NOT NULL DEFAULT now(),
    CHECK (hasta >= desde),
    CHECK (hora_fin > hora_inicio)
);

CREATE INDEX horario_medico_idx ON prestadores.horario (medico_id);
