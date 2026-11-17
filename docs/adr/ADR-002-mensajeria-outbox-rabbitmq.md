# ADR-002: Mensajería asíncrona con outbox transaccional sobre RabbitMQ

**Estado:** Aceptada · 2026-11-05

## Contexto

Cada cita debe notificarse y llegar a la HCE sin que una falla externa bloquee o deshaga la reserva (drivers 1 y 4). Publicar al broker después del commit pierde eventos si el proceso cae entre los dos pasos.

## Decisión

La API guarda la cita y el evento en la tabla **outbox** dentro de la misma transacción; un relé los publica en **RabbitMQ** (3 nodos, colas quorum). Los consumidores son idempotentes y los mensajes fallidos van a una cola de mensajes muertos (DLQ).

## Alternativas consideradas

### HTTP síncrono a WhatsApp y HCE — DESCARTADA

La latencia y las caídas de terceros se suman a cada reserva; la disponibilidad del núcleo quedaría atada a la de ellos.

### Apache Kafka — DESCARTADA

Miles de eventos al día no justifican operar particiones y retención; no hace falta releer el historial y RabbitMQ ya trae reintento y DLQ por mensaje.

### Tarea que consulta la BD cada minuto — DESCARTADA

Los consumidores leerían tablas del núcleo (acoplamiento de esquema) y cada aviso se retrasaría hasta el siguiente ciclo.

## Consecuencias

**Buenas**

Ningún evento se pierde aunque caiga la API · se agregan consumidores sin tocar al productor · un tercero caído no frena las reservas.

**Malas**

Entrega «al menos una vez»: hay que tolerar duplicados · el aviso llega segundos después · relé y broker son dos piezas más que vigilar.

---

**En el esqueleto:** tabla `agenda.outbox` y relé en el módulo `agenda` de `servicios/agendamiento/`; broker de 3 nodos `rabbitmq-1..3` con colas quorum, reintento y DLQ (contratos en `contratos/eventos/`); consumidores idempotentes por `eventoId` en `servicios/notificaciones/` y `servicios/integracion-clinica/`. Se demuestra con `docker compose run --rm escenarios broker-caido`.
