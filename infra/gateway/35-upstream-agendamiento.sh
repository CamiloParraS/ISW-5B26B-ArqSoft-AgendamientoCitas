#!/bin/sh
# Genera el upstream de la API de Agendamiento (2 instancias).
# "resolve" (nginx >= 1.27.3) re-resuelve los nombres con el resolver de Docker en tiempo de
# ejecución: el gateway arranca aunque una instancia no exista todavía y sigue a una instancia
# recreada con otra IP sin recargar nginx (failover de la táctica "Redundancia activa").
set -e
OUT=/etc/nginx/conf.d/upstream-agendamiento.inc
{
  echo "upstream agendamiento {"
  echo "    zone agendamiento 64k;"
  for h in agendamiento-1 agendamiento-2; do
    echo "    server $h:8080 resolve max_fails=1 fail_timeout=10s;"
  done
  echo "}"
} > "$OUT"
cat "$OUT"
