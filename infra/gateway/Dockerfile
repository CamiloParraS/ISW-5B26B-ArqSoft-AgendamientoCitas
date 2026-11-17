# API Gateway de MediCita (NGINX): TLS, enrutamiento, balanceo, limite de tasa, correlacion
FROM nginx:1.27.5-alpine

RUN apk add --no-cache openssl curl \
 && rm -f /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/nginx.conf
COPY redirect-http.conf.template /etc/nginx/templates/redirect-http.conf.template
COPY 30-certificado.sh /docker-entrypoint.d/30-certificado.sh
COPY 35-upstream-agendamiento.sh /docker-entrypoint.d/35-upstream-agendamiento.sh
RUN chmod 0755 /docker-entrypoint.d/30-certificado.sh /docker-entrypoint.d/35-upstream-agendamiento.sh

# Usado por la plantilla del redirect 80 -> https (incluye el puerto publicado en el host)
ENV MEDICITA_PUBLIC_URL=https://localhost
EXPOSE 80 443
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
  CMD curl -fsS http://127.0.0.1:80/estado || exit 1
