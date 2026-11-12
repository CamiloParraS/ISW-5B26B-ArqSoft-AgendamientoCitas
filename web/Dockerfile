# Aplicación web [Contenedor: React] · se despliega aparte de la Aplicación de agenda
FROM node:22-alpine AS construccion
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY tls.sh /docker-entrypoint.d/40-tls.sh
RUN chmod +x /docker-entrypoint.d/40-tls.sh
COPY --from=construccion /web/dist /usr/share/nginx/html
