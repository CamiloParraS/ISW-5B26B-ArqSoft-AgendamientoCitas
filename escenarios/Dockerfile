# Escenarios de calidad de MediCita (E1, E2, E3 y verificaciones de tacticas)
FROM python:3.13.18-alpine3.23

RUN apk add --no-cache docker-cli postgresql-client curl

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONIOENCODING=utf-8

WORKDIR /app
COPY *.py /app/

ENTRYPOINT ["python", "/app/main.py"]
