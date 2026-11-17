#!/bin/sh
# Genera un certificado autofirmado (SAN localhost, 127.0.0.1, gateway) si no existe.
set -e
DIR=/etc/nginx/certs
mkdir -p "$DIR"
if [ ! -s "$DIR/medicita.crt" ] || [ ! -s "$DIR/medicita.key" ]; then
  echo "30-certificado: generando certificado autofirmado para localhost"
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
    -keyout "$DIR/medicita.key" -out "$DIR/medicita.crt" \
    -subj "/CN=localhost/O=MediCita" \
    -addext "subjectAltName=DNS:localhost,DNS:gateway,IP:127.0.0.1" 2>/dev/null
  chmod 600 "$DIR/medicita.key"
fi
