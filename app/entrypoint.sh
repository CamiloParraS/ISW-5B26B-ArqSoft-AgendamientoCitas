#!/bin/sh
# Arranque de la Aplicación de agenda: migra, siembra datos de demostración y lanza gunicorn.
# PID 1 es el init mínimo de Docker (init: true); el maestro de gunicorn deja su PID en /tmp/gunicorn.pid
# para que el chequeo de salud pueda terminarlo si deja de responder.
set -e
date +%s > /tmp/arranque
echo 0 > /tmp/fallos_salud
python manage.py migrate --noinput
python manage.py sembrar
exec gunicorn config.wsgi:application \
  --pid /tmp/gunicorn.pid \
  --bind 0.0.0.0:8000 \
  --worker-class gthread \
  --workers "${GUNICORN_WORKERS:-3}" \
  --threads "${GUNICORN_THREADS:-8}" \
  --timeout 30 \
  --access-logfile -
