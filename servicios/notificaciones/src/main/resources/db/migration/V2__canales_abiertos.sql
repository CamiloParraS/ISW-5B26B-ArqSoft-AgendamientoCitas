-- Los canales son un identificador abierto: agregar uno no requiere migración
ALTER TABLE notificaciones.destinatario DROP CONSTRAINT IF EXISTS destinatario_canal_preferido_check;
ALTER TABLE notificaciones.envio DROP CONSTRAINT IF EXISTS envio_canal_check;
