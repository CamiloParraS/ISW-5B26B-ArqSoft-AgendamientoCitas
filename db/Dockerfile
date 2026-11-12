# Base de datos [Contenedor: PostgreSQL] · "Lee y escribe [SQL/TLS]"
# Certificado autofirmado para que la conexión desde la aplicación vaya cifrada (sslmode=require).
FROM postgres:16-alpine

RUN apk add --no-cache openssl \
 && mkdir -p /etc/postgresql/tls \
 && openssl req -new -x509 -days 3650 -nodes -subj "/CN=base-de-datos" \
      -keyout /etc/postgresql/tls/server.key -out /etc/postgresql/tls/server.crt \
 && chown -R postgres:postgres /etc/postgresql/tls \
 && chmod 600 /etc/postgresql/tls/server.key

CMD ["postgres", \
     "-c", "ssl=on", \
     "-c", "ssl_cert_file=/etc/postgresql/tls/server.crt", \
     "-c", "ssl_key_file=/etc/postgresql/tls/server.key"]
