# ADR-003: Canales de notificación como adaptadores enchufables

**Estado:** Aceptada · 2026-11-05

## Contexto

El cliente pide canales modernos como WhatsApp y prevé más (driver 5). WhatsApp sólo permite iniciar conversación con plantillas aprobadas por Meta; SMS y correo tienen reglas y costos propios.

## Decisión

El Servicio de Notificaciones define el puerto **CanalNotificacion** (enviar, consultarEstado). Cada canal es un **adaptador** registrado por configuración. El canal sale de la preferencia del paciente, con respaldo WhatsApp → SMS → correo.

## Alternativas consideradas

### Lógica de canales dentro de Agenda — DESCARTADA

Cada canal nuevo cambia y redespliega el núcleo, y mezcla el ritmo de cambio alto de la mensajería con el de la agenda.

### Un proveedor multicanal como diseño — DESCARTADA

Atar todo a Twilio o Infobip fija un contrato y un precio por mensaje; ese proveedor queda como un adaptador más, no como la arquitectura.

### Un microservicio por canal — DESCARTADA

Cada canal es poca lógica: serían N despliegues, N pipelines y N alertas para algo que cabe en una clase.

## Consecuencias

**Buenas**

Canal nuevo = adaptador + configuración, sin tocar Agenda · si un canal falla, el respaldo es automático.

**Malas**

El puerto es un mínimo común (los botones de WhatsApp no existen en SMS) · hay plantillas por canal que mantener · cada adaptador se prueba contra un simulador.

---

**En el esqueleto:** puerto `CanalNotificacion` y adaptadores `WhatsAppAdaptador`, `SmsAdaptador`, `CorreoAdaptador` en `servicios/notificaciones/` (paquete `co.edu.ucc.medicita.notificaciones`), activados por `medicita.notificaciones.canales.<canal>.habilitado`; cada uno se prueba contra el servicio `simuladores` (`externos/simuladores/`) y Mailpit. Se demuestra con `docker compose run --rm escenarios respaldo-canal`.
