# Cómo agregar un canal de notificación (hoja del árbol de utilidad: nuevo canal en ≤ 5 días)

Táctica: **intermediario y puerto-adaptador** (ADR-003). Agenda sólo publica `CitaAgendada`, `CitaCancelada` y
`CitaAtendida`; no sabe si el aviso sale por WhatsApp, SMS, correo o un canal futuro. **No cambia nada en Agenda**
ni en su despliegue: sólo se vuelve a construir y desplegar este servicio.

## Los pasos (una clase + configuración + plantilla)

1. **Una clase adaptadora** en `co.edu.ucc.medicita.notificaciones.adaptadores` que implemente el puerto
   `CanalNotificacion` (`canal()`, `enviar(Mensaje)`, `consultarEstado(idExterno)`), siguiendo el patrón de
   `SmsAdaptador`. El canal es un identificador abierto: `Canal.of("TELEGRAM")`.

   ```java
   @Component
   @ConditionalOnProperty(prefix = "medicita.notificaciones.canales.telegram", name = "habilitado", havingValue = "true")
   @EnableConfigurationProperties(TelegramAdaptador.Config.class)
   public class TelegramAdaptador implements CanalNotificacion {
       public Canal canal() { return Canal.of("TELEGRAM"); }
       ...
   }
   ```

   Reglas: no lanzar excepciones por fallas del proveedor; devolver `ResultadoEnvio.rechazado(...)` si el proveedor
   rechaza (4xx) y `ResultadoEnvio.fallido(...)` si hay error de red o 5xx.
2. **Configuración y plantilla** en `application.yml` (o variables de entorno):

   ```yaml
   medicita.notificaciones:
     respaldo: WHATSAPP,SMS,CORREO,TELEGRAM     # sólo si debe entrar en el orden de respaldo
     canales:
       telegram:
         habilitado: true
         contacto: telefono                      # dato que necesita: telefono | email
         plantillas:
           CONFIRMACION: "..."                   # CONFIRMACION, CANCELACION, RECORDATORIO
   ```

   Un paciente puede tener `TELEGRAM` como `canal_preferido` (la columna es texto libre; no hay enum ni restricción CHECK).

No hay enum que ampliar ni migración que escribir.

## Qué NO hay que tocar

- `SelectorDeCanales`: recibe todos los adaptadores como `List<CanalNotificacion>` (inyección de los beans habilitados).
  Aplica solo la política "canal preferido y luego respaldo", registra cada intento en `envio` y se detiene en el primer
  `ENVIADO`.
- El consumidor de la cola `notificaciones.citas`, la idempotencia por `eventoId`, los reintentos y la DLQ.
- El servicio de Agenda, sus eventos y el broker.

## Prueba

Cada adaptador se prueba contra un simulador (ver `NotificacionesIT`: servidor HTTP de pruebas para WhatsApp y SMS,
GreenMail para SMTP). Para el canal nuevo basta un simulador equivalente y un caso en la IT que lo habilite y verifique
la fila en `envio`.

## Estimación

| Paso | Esfuerzo |
|---|---|
| Adaptador + configuración + plantillas | 1 a 2 días |
| Simulador y prueba de integración | 1 a 2 días |
| Despliegue de este servicio (Agenda intacta) | 0,5 día |

Total: dentro de los 5 días de la hoja del árbol de utilidad.
