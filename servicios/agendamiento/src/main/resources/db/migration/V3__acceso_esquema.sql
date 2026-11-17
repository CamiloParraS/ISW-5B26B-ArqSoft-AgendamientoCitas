-- Contexto Acceso interinstitucional: instituciones, consentimientos y bitácora inmutable
CREATE SCHEMA IF NOT EXISTS acceso;

CREATE TABLE acceso.institucion (
    codigo text PRIMARY KEY,
    nombre text NOT NULL
);

CREATE TABLE acceso.consentimiento (
    id                 uuid PRIMARY KEY,
    paciente_id        uuid NOT NULL,
    institucion_codigo text NOT NULL REFERENCES acceso.institucion (codigo),
    otorgado_en        timestamptz NOT NULL DEFAULT now(),
    vigente_hasta      timestamptz NOT NULL,
    revocado_en        timestamptz
);

CREATE INDEX consentimiento_paciente_idx ON acceso.consentimiento (paciente_id, institucion_codigo);

CREATE TABLE acceso.bitacora_acceso (
    id                   bigserial PRIMARY KEY,
    ocurrido_en          timestamptz NOT NULL DEFAULT now(),
    usuario_sub          text NOT NULL,
    usuario_nombre       text,
    institucion_codigo   text,
    documento_consultado text NOT NULL,
    paciente_id          uuid,
    resultado            text NOT NULL CHECK (resultado IN ('PERMITIDO', 'DENEGADO')),
    motivo               text,
    correlacion_id       text
);

CREATE INDEX bitacora_paciente_idx ON acceso.bitacora_acceso (paciente_id, ocurrido_en DESC);

-- La bitácora sólo admite inserciones (Ley 1581: trazabilidad)
CREATE FUNCTION acceso.bitacora_inmutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'bitacora_acceso solo admite inserciones';
END;
$$;

CREATE TRIGGER bitacora_sin_modificar
    BEFORE UPDATE OR DELETE ON acceso.bitacora_acceso
    FOR EACH ROW EXECUTE FUNCTION acceso.bitacora_inmutable();

CREATE TRIGGER bitacora_sin_truncar
    BEFORE TRUNCATE ON acceso.bitacora_acceso
    FOR EACH STATEMENT EXECUTE FUNCTION acceso.bitacora_inmutable();
