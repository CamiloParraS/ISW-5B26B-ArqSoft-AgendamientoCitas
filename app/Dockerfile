# Una sola imagen, dos contenedores del C4 Nivel 2:
#   target "aplicacion" -> Aplicación de agenda [Python / Django]  (gunicorn, un proceso)
#   target "tarea"      -> Tarea programada [cron]  que ejecuta el comando "barrido" cada 5 min
FROM python:3.12-slim AS aplicacion

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN chmod +x entrypoint.sh tarea/arrancar.sh

EXPOSE 8000
CMD ["./entrypoint.sh"]


FROM aplicacion AS tarea

RUN apt-get update \
 && apt-get install -y --no-install-recommends cron \
 && rm -rf /var/lib/apt/lists/* \
 && cp tarea/crontab /etc/cron.d/barrido \
 && chmod 0644 /etc/cron.d/barrido

CMD ["./tarea/arrancar.sh"]
