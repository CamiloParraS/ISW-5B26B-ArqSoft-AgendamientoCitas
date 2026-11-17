#!/bin/sh
# Certificados TLS del gateway, persistidos en infra/gateway/certs (montado, ignorado por git).
#  - medicita-ca.crt/.key: autoridad local con nameConstraints: SOLO puede firmar localhost,
#    gateway, 127.0.0.1 y ::1. Aunque su llave se filtrara, no sirve para suplantar otro sitio.
#    Se instala una vez en el almacén de confianza con infra/gateway/confiar-certificado.ps1.
#  - medicita.crt/.key: certificado del servidor firmado por esa autoridad.
# Si ya existen se reutilizan, así la confianza sobrevive a recrear contenedores y a "down -v".
set -e
DIR=/etc/nginx/certs
mkdir -p "$DIR"
cd "$DIR"

if [ ! -s medicita-ca.crt ] || [ ! -s medicita-ca.key ]; then
  echo "30-certificado: generando autoridad local MediCita (restringida a localhost)"
  cat > /tmp/ca.cnf <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = MediCita CA local (solo localhost)
O = MediCita
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
nameConstraints = critical, permitted;DNS:localhost, permitted;DNS:gateway, permitted;IP:127.0.0.0/255.0.0.0, permitted;IP:0:0:0:0:0:0:0:1/FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF
EOF
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config /tmp/ca.cnf \
    -keyout medicita-ca.key -out medicita-ca.crt 2>/dev/null
  rm -f medicita.crt medicita.key
fi

if [ ! -s medicita.crt ] || [ ! -s medicita.key ]; then
  echo "30-certificado: generando certificado del servidor para localhost"
  cat > /tmp/srv.cnf <<'EOF'
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:gateway, IP:127.0.0.1, IP:0:0:0:0:0:0:0:1
EOF
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost/O=MediCita" \
    -keyout medicita.key -out /tmp/srv.csr 2>/dev/null
  # 397 días: el máximo que aceptan los navegadores para certificados de servidor
  openssl x509 -req -in /tmp/srv.csr -CA medicita-ca.crt -CAkey medicita-ca.key -CAcreateserial \
    -days 397 -sha256 -extfile /tmp/srv.cnf -out medicita.crt 2>/dev/null
  cat medicita-ca.crt >> medicita.crt   # cadena completa
fi
chmod 600 medicita.key medicita-ca.key 2>/dev/null || true
