package co.edu.ucc.medicita.notificaciones.mensajeria;

import co.edu.ucc.medicita.notificaciones.aplicacion.ExcepcionPermanente;
import co.edu.ucc.medicita.notificaciones.aplicacion.NotificacionesProps;
import co.edu.ucc.medicita.notificaciones.aplicacion.ProcesadorDeEventos;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumidor de notificaciones.citas con ack manual.
 * Falla transitoria: copia a notificaciones.citas.reintento con x-intentos+1 y ack del original.
 * Falla permanente (mal formado o más de maxIntentos): reject sin reencolar, va a la DLQ.
 */
@Component
public class ConsumidorCitas {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorCitas.class);
    static final String CABECERA_CORRELACION = "x-correlation-id";
    static final String CABECERA_INTENTOS = "x-intentos";

    private final ProcesadorDeEventos procesador;
    private final RabbitTemplate rabbit;
    private final NotificacionesProps props;

    public ConsumidorCitas(ProcesadorDeEventos procesador, RabbitTemplate rabbit, NotificacionesProps props) {
        this.procesador = procesador;
        this.rabbit = rabbit;
        this.props = props;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA)
    public void alRecibir(Message mensaje, Channel canal) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();
        Object corr = mensaje.getMessageProperties().getHeader(CABECERA_CORRELACION);
        String correlacion = corr == null ? null : corr.toString();
        if (correlacion != null) MDC.put("correlacionId", correlacion);
        try {
            procesador.procesar(mensaje.getBody(), correlacion);
            canal.basicAck(tag, false);
        } catch (ExcepcionPermanente e) {
            log.error("Evento mal formado, va a la DLQ: {}", e.getMessage());
            canal.basicReject(tag, false);
        } catch (RuntimeException e) {
            reintentarODescartar(mensaje, canal, tag, e);
        } finally {
            MDC.remove("correlacionId");
        }
    }

    private void reintentarODescartar(Message original, Channel canal, long tag, RuntimeException causa)
            throws IOException {
        int siguiente = intentosDe(original) + 1;
        if (siguiente > props.maxIntentos()) {
            log.error("Evento agotó {} reintentos, va a la DLQ: {}", props.maxIntentos(), causa.getMessage());
            canal.basicReject(tag, false);
            return;
        }
        MessageProperties p = new MessageProperties();
        MessageProperties o = original.getMessageProperties();
        p.setMessageId(o.getMessageId());
        p.setType(o.getType());
        p.setContentType(o.getContentType());
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Object corr = o.getHeader(CABECERA_CORRELACION);
        if (corr != null) p.setHeader(CABECERA_CORRELACION, corr);
        p.setHeader(CABECERA_INTENTOS, siguiente);
        Message copia = new Message(original.getBody(), p);
        try {
            rabbit.invoke(ops -> {
                ops.send("", TopologiaRabbit.COLA_REINTENTO, copia);
                ops.waitForConfirmsOrDie(5000);
                return null;
            });
            canal.basicAck(tag, false);
            log.warn("Falla transitoria ({}); reintento {} de {} en 30 s", causa.getMessage(), siguiente,
                    props.maxIntentos());
        } catch (RuntimeException e) {
            // No se pudo publicar el reintento: se devuelve el original para no perderlo
            log.error("No se pudo publicar el reintento; se reencola el original: {}", e.getMessage());
            canal.basicNack(tag, false, true);
        }
    }

    private static int intentosDe(Message m) {
        Object v = m.getMessageProperties().getHeader(CABECERA_INTENTOS);
        return v instanceof Number n ? n.intValue() : 0;
    }
}
