#!/bin/sh
# Contenedor "Tarea programada": cron no hereda el entorno del contenedor, así que lo dejamos en un archivo.
set -e
export -p > /etc/entorno-tarea.sh
echo "[tarea] primer barrido al arrancar"
python manage.py barrido || echo "[tarea] el primer barrido falló; cron lo reintentará en 5 min"
echo "[tarea] cron activo: barrido cada 5 min"
exec cron -f
