# PostgreSQL 16 + Patroni (DCS etcd v3) para la BD de Agendamiento (primaria + réplica)
FROM postgres:16.16-bookworm

# patroni viene del repositorio PGDG ya configurado en la imagen base
RUN apt-get update \
 && apt-get install -y --no-install-recommends patroni python3-etcd3 curl \
 && rm -rf /var/lib/apt/lists/*

COPY patroni.yml /etc/patroni.yml
COPY post_bootstrap.sh /post_bootstrap.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod 0755 /post_bootstrap.sh /entrypoint.sh

ENV PATRONI_CONFIG=/etc/patroni.yml
EXPOSE 5432 8008
VOLUME /var/lib/postgresql/data
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8008/health || exit 1
ENTRYPOINT ["/entrypoint.sh"]
