# Contratos de eventos de MediCita (v1)

Eventos que el núcleo de **Agenda** publica en el **Broker** (RabbitMQ) y que consumen los satélites
(**Notificaciones** e **Integración Clínica**). Los esquemas son JSON Schema 2020-12:

| Evento | Routing key | Esquema |
|---|---|---|
| `CitaAgendada` | `cita.agendada.v1` | [`cita-agendada.v1.json`](cita-agendada.v1.json) |
| `CitaCancelada` | `cita.cancelada.v1` | [`cita-cancelada.v1.json`](cita-cancelada.v1.json) |
| `CitaAtendida` | `cita.atendida.v1` | [`cita-atendida.v1.json`](cita-atendida.v1.json) |

## Sobre (envelope)

```json
{
  "eventoId": "uuid",
  "tipo": "CitaAgendada | CitaCancelada | CitaAtendida",
  "version": 1,
  "ocurridoEn": "2026-11-17T12:00:00-05:00",
  "correlacionId": "string",
  "datos": { }
}
```

Fechas en ISO-8601 con desfase (zona de dominio `America/Bogota`). Los esquemas usan
`additionalProperties: false`: un campo nuevo es un cambio de contrato y se versiona (ver más abajo).

**Sin datos personales.** Solo `pacienteId` y `citaId` identifican entidades entre contextos; ni documento,
ni nombre del paciente, ni teléfono viajan en los eventos (Ley 1581 de 2012). Cada consumidor resuelve lo que
necesita en su propio contexto (Notificaciones tiene sus destinatarios; Integración Clínica consulta la HCE).

## Mensajería AMQP

- **Exchange**: `medicita.citas`, tipo `topic`, durable. Lo declaran el productor **y** los consumidores
  (declaración idempotente), así el orden de arranque no importa.
- **Propiedades del mensaje**: `message_id` = `eventoId`, `type` = `tipo`, `content_type` = `application/json`,
  `delivery_mode` = 2 (persistente); cabecera `x-correlation-id`.
- **Publicación fiable** (patrón outbox): el evento se inserta en `agenda.outbox` en la misma transacción que
  la reserva; un relé lo publica con *publisher confirms* y `mandatory`. Si el broker cae, la reserva sigue
  funcionando y el evento queda pendiente (entrega *al menos una vez*).

## Colas de consumo

Cada consumidor declara su propia cola (todas `x-queue-type=quorum`, durables):

| Cola | Routing keys enlazadas |
|---|---|
| `notificaciones.citas` | `cita.agendada.v1`, `cita.cancelada.v1` |
| `integracion-clinica.citas` | `cita.agendada.v1`, `cita.cancelada.v1`, `cita.atendida.v1` |

## Reintento y cola de mensajes fallidos (DLQ)

Para cada cola `Q`:

- Argumentos de `Q`: `x-dead-letter-exchange=medicita.dlx`, `x-dead-letter-routing-key=Q`,
  `x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish`.
- **DLX** `medicita.dlx` (direct, durable) → cola `Q.dlq` (quorum), enlazada con la clave `Q`.
- **Cola de reintento** `Q.reintento` (quorum) con `x-message-ttl=30000`, `x-dead-letter-exchange=""` y
  `x-dead-letter-routing-key=Q`: al vencer los 30 s el mensaje vuelve a `Q` por el exchange por defecto.

```
medicita.citas --(rk v1)--> Q --fallo transitorio--> Q.reintento --(30 s TTL)--> Q
                             |
                             +--fallo permanente / x-intentos > máx--> medicita.dlx --(rk=Q)--> Q.dlq
```

Política del consumidor:

- **Fallo transitorio** (canal caído, HCE no disponible): publicar una copia en `Q.reintento` por el exchange por
  defecto con la cabecera `x-intentos` incrementada y hacer `ack` del original.
- **Fallo permanente** (mensaje malformado, o `x-intentos` por encima del máximo): `reject` sin reencolar
  → `medicita.dlx` → `Q.dlq`, para revisión manual.

## Idempotencia

La entrega es *al menos una vez*, por lo que habrá duplicados. Todo consumidor es idempotente por `eventoId`:

- Notificaciones registra `eventoId` en `evento_procesado` en la misma transacción que los envíos.
- Integración Clínica usa operaciones condicionales FHIR (`If-None-Exist` por identificador de cita).

## Política de versionado

- La versión va en el nombre del esquema, en el campo `version` y en la routing key (`.v1`).
- Como los esquemas son cerrados (`additionalProperties: false`), todo campo nuevo, renombrado, eliminado o con otro
  significado es un cambio incompatible y exige una versión nueva.
- Una versión incompatible = **nueva routing key `.v2`** y un esquema `*.v2.json`. Durante la transición el productor
  publica **ambas** (`.v1` y `.v2`) del mismo hecho; los consumidores migran enlazando su cola a `.v2` y, cuando ya
  no queden consumidores de `.v1`, se retira su publicación.
- Los esquemas publicados nunca se modifican en sitio; se añaden archivos nuevos y se documenta aquí el cambio.
