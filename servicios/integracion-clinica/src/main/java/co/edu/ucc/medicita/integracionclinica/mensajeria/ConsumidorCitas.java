package co.edu.ucc.medicita.integracionclinica.mensajeria;

import co.edu.ucc.medicita.integracionclinica.aplicacion.SincronizadorHce;
import co.edu.ucc.medicita.integracionclinica.eventos.Evento;
import co.edu.ucc.medicita.integracionclinica.eventos.EventoMalformadoException;
import co.edu.ucc.medicita.integracionclinica.eventos.LectorEventos;
import co.edu.ucc.medicita.integracionclinica.hce.FalloDeDisponibilidad;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consumidor de integracion-clinica.citas.
 * - Falla de disponibilidad (breaker abierto, E/S, timeout, 5xx): copia a la cola de reintento, sin límite.
 * - Falla permanente (4xx/validación): reintento hasta el máximo de intentos y luego DLQ.
 * - Mensaje malformado: DLQ de inmediato.
 */
@Component
public class ConsumidorCitas {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorCitas.class);

    static final String CABECERA_INTENTOS = "x-intentos";
    static final String CABECERA_REINTENTOS_DISPONIBILIDAD = "x-reintentos-disponibilidad";
    static final String CABECERA_CORRELACION = "x-correlation-id";
    static final String MDC_CORRELACION = "correlacionId";

    private final LectorEventos lector;
    private final SincronizadorHce sincronizador;
    private final RabbitTemplate plantilla;
    private final int maxIntentos;

    public ConsumidorCitas(LectorEventos lector, SincronizadorHce sincronizador, RabbitTemplate plantilla,
                           @Value("${medicita.mensajeria.max-intentos-validacion}") int maxIntentos) {
        this.lector = lector;
        this.sincronizador = sincronizador;
        this.plantilla = plantilla;
        this.maxIntentos = maxIntentos;
    }

    @RabbitListener(queues = ConfiguracionMensajeria.COLA)
    public void alRecibir(Message mensaje) {
        String correlacion = cabecera(mensaje, CABECERA_CORRELACION);
        if (correlacion != null) {
            MDC.put(MDC_CORRELACION, correlacion);
        }
        try {
            Evento evento;
            try {
                evento = lector.leer(mensaje.getBody());
            } catch (EventoMalformadoException e) {
                log.error("Mensaje malformado, va a la DLQ: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Evento malformado", e);
            }
            if (correlacion == null) {
                correlacion = evento.correlacionId() != null ? evento.correlacionId() : UUID.randomUUID().toString();
                MDC.put(MDC_CORRELACION, correlacion);
            }
            procesar(mensaje, evento);
        } finally {
            MDC.remove(MDC_CORRELACION);
        }
    }

    private void procesar(Message mensaje, Evento evento) {
        try {
            sincronizador.procesar(evento.datos());
            log.info("Evento {} ({}) sincronizado con la HCE", evento.eventoId(), evento.tipo());
        } catch (RuntimeException e) {
            if (FalloDeDisponibilidad.esDisponibilidad(e)) {
                // No consume el presupuesto de x-intentos: solo se cuenta de forma informativa
                Message copia = copiaParaReintento(mensaje, true);
                log.warn("HCE no disponible ({}); evento {} a reintento (reintentos por disponibilidad: {})",
                        e.getClass().getSimpleName(), evento.eventoId(), entero(copia, CABECERA_REINTENTOS_DISPONIBILIDAD));
                reintentar(copia);
                return;
            }
            int intentos = intentos(mensaje) + 1;
            if (intentos >= maxIntentos) {
                log.error("Evento {} rechazado por la HCE tras {} intentos; va a la DLQ: {}",
                        evento.eventoId(), intentos, e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Reintentos agotados", e);
            }
            log.warn("Evento {} rechazado por la HCE (intento {}/{}): {}",
                    evento.eventoId(), intentos, maxIntentos, e.getMessage());
            reintentar(copiaParaReintento(mensaje, false));
        }
    }

    /** x-intentos cuenta solo fallos no transitorios; la disponibilidad se cuenta aparte (informativo). */
    static Message copiaParaReintento(Message original, boolean disponibilidad) {
        var constructor = MessageBuilder.fromMessage(original);
        if (disponibilidad) {
            constructor.setHeader(CABECERA_REINTENTOS_DISPONIBILIDAD, entero(original, CABECERA_REINTENTOS_DISPONIBILIDAD) + 1);
        } else {
            constructor.setHeader(CABECERA_INTENTOS, entero(original, CABECERA_INTENTOS) + 1);
        }
        return constructor.build();
    }

    private void reintentar(Message copia) {
        plantilla.invoke(operaciones -> {
            operaciones.send("", ConfiguracionMensajeria.COLA_REINTENTO, copia);
            operaciones.waitForConfirmsOrDie(5000);
            return null;
        });
    }

    private static int intentos(Message mensaje) {
        return entero(mensaje, CABECERA_INTENTOS);
    }

    static int entero(Message mensaje, String cabecera) {
        Object v = mensaje.getMessageProperties().getHeader(cabecera);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignorada) {
                return 0;
            }
        }
        return 0;
    }

    private static String cabecera(Message mensaje, String nombre) {
        Object v = mensaje.getMessageProperties().getHeader(nombre);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }
}
