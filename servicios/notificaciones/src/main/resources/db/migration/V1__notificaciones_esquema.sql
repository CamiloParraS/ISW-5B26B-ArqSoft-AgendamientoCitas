CREATE SCHEMA IF NOT EXISTS notificaciones;

CREATE TABLE notificaciones.destinatario (
    paciente_id      uuid PRIMARY KEY,
    nombre           text NOT NULL,
    telefono         text,
    email            text,
    canal_preferido  text NOT NULL CHECK (canal_preferido IN ('WHATSAPP','SMS','CORREO'))
);

CREATE TABLE notificaciones.envio (
    id              uuid PRIMARY KEY,
    evento_id       uuid,
    cita_id         uuid NOT NULL,
    paciente_id     uuid NOT NULL,
    tipo            text NOT NULL CHECK (tipo IN ('CONFIRMACION','CANCELACION','RECORDATORIO')),
    canal           text NOT NULL CHECK (canal IN ('WHATSAPP','SMS','CORREO')),
    estado          text NOT NULL CHECK (estado IN ('ENVIADO','RECHAZADO','FALLIDO')),
    id_externo      text,
    detalle         text,
    correlacion_id  text,
    creado_en       timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX envio_cita_idx ON notificaciones.envio (cita_id, creado_en);

CREATE TABLE notificaciones.evento_procesado (
    evento_id     uuid PRIMARY KEY,
    procesado_en  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notificaciones.cita_por_recordar (
    cita_id        uuid PRIMARY KEY,
    paciente_id    uuid NOT NULL,
    inicio         timestamptz NOT NULL,
    medico_nombre  text NOT NULL,
    sede_nombre    text NOT NULL,
    recordada      boolean NOT NULL DEFAULT false
);
CREATE INDEX cita_por_recordar_inicio_idx ON notificaciones.cita_por_recordar (inicio) WHERE NOT recordada;

-- Destinatarios sembrados (datos compartidos del esqueleto)
INSERT INTO notificaciones.destinatario (paciente_id, nombre, telefono, email, canal_preferido) VALUES
 ('a0000000-0000-4000-8000-000000000001', 'Ana Gómez',  '+573001110001', 'ana@medicita.test',  'WHATSAPP'),
 ('a0000000-0000-4000-8000-000000000002', 'Luis Pérez', '+573001110002', 'luis@medicita.test', 'SMS');

INSERT INTO notificaciones.destinatario (paciente_id, nombre, telefono, email, canal_preferido)
SELECT format('a0000000-0000-4000-8000-%s', lpad((100 + n)::text, 12, '0'))::uuid,
       'Paciente Carga ' || lpad(n::text, 2, '0'),
       '+5730022200' || lpad(n::text, 2, '0'),
       'carga' || lpad(n::text, 2, '0') || '@medicita.test',
       'CORREO'
FROM generate_series(1, 50) AS n;
