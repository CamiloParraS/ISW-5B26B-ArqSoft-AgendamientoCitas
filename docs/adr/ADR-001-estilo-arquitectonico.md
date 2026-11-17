# ADR-001: Monolito modular para el núcleo y servicios satélite por eventos

**Estado:** Aceptada · 2026-11-05

## Contexto

Reservar un cupo exige consistencia inmediata (driver 2) y el agendamiento debe estar siempre disponible (driver 1). La HCE y WhatsApp son externos, a veces lentos o caídos, y los recordatorios llegan en picos distintos a los de las reservas.

## Decisión

El núcleo (Agenda, Red de prestadores y Acceso interinstitucional) es un **monolito modular** en Spring Boot con Spring Modulith, desplegado en 2+ instancias. **Notificaciones** e **Integración clínica** son servicios separados que reaccionan a eventos.

## Alternativas consideradas

### Microservicios por contexto — DESCARTADA

Reservar toca Agenda y Prestadores en la misma operación: separarlos obliga a sagas y consistencia eventual justo donde no se tolera doble asignación. Cada salto síncrono resta disponibilidad (3 × 99,9 % ≈ 99,7 %).

### Un solo monolito en capas — DESCARTADA

Una HCE lenta retiene hilos del mismo proceso que agenda y un pico de recordatorios compite por CPU con las reservas; sumar un canal obliga a redesplegar el núcleo.

### Todo dirigido por eventos — DESCARTADA

El paciente necesita saber en el acto si obtuvo el cupo; la reserva no puede ser eventualmente consistente.

## Consecuencias

**Buenas**

La reserva es una transacción local · las fallas externas quedan aisladas · las fronteras entre módulos se verifican en cada build.

**Malas**

El núcleo escala como un bloque · hay dos estilos que operar · los satélites requieren trazas distribuidas · la BD primaria sigue siendo punto crítico.

---

**En el esqueleto:** el núcleo vive en `servicios/agendamiento/` (módulos `prestadores`, `agenda`, `acceso` de Spring Modulith, 2 instancias `agendamiento-1` y `agendamiento-2`); los satélites son `servicios/notificaciones/` y `servicios/integracion-clinica/`. La verificación de fronteras es el test `ModularidadTest` que corre en cada build (`docker compose build agendamiento-1`).
