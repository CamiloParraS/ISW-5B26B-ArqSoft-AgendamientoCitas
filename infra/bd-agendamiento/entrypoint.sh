#!/bin/bash
# Prepara el directorio de datos y arranca Patroni como usuario postgres.
set -e
: "${PATRONI_NAME:?PATRONI_NAME es obligatorio}"
export PATRONI_RESTAPI_CONNECT_ADDRESS="${PATRONI_RESTAPI_CONNECT_ADDRESS:-${PATRONI_NAME}:8008}"
export PATRONI_POSTGRESQL_CONNECT_ADDRESS="${PATRONI_POSTGRESQL_CONNECT_ADDRESS:-${PATRONI_NAME}:5432}"
mkdir -p /var/lib/postgresql/data/pgdata
chown postgres:postgres /var/lib/postgresql/data /var/lib/postgresql/data/pgdata
chmod 700 /var/lib/postgresql/data/pgdata
exec gosu postgres patroni /etc/patroni.yml
