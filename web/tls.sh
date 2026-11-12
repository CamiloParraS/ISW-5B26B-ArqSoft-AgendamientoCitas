#!/bin/sh
# Certificado autofirmado para servir la aplicación por HTTPS en https://localhost:8443
set -e
if [ ! -f /etc/nginx/tls/web.crt ]; then
  mkdir -p /etc/nginx/tls
  openssl req -new -x509 -days 3650 -nodes -subj "/CN=localhost" \
    -keyout /etc/nginx/tls/web.key -out /etc/nginx/tls/web.crt 2>/dev/null
fi
