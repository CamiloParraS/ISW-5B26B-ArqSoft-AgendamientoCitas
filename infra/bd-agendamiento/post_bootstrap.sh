#!/bin/bash
# Ejecutado por Patroni una sola vez, en la primaria inicial. $1 = cadena de conexión.
set -e
psql -v ON_ERROR_STOP=1 "$1" <<'SQL'
CREATE ROLE medicita LOGIN PASSWORD 'medicita';
CREATE DATABASE medicita OWNER medicita ENCODING 'UTF8';
SQL
