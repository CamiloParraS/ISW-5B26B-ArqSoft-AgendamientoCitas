#!/bin/bash
# RabbitMQ 4 ya no lee RABBITMQ_ERLANG_COOKIE: escribimos la cookie compartida (perm. 400) y delegamos.
set -e
: "${COOKIE_ERLANG:?COOKIE_ERLANG es obligatoria}"
printf '%s' "$COOKIE_ERLANG" > /var/lib/rabbitmq/.erlang.cookie
chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie
chmod 400 /var/lib/rabbitmq/.erlang.cookie
exec docker-entrypoint.sh "$@"
