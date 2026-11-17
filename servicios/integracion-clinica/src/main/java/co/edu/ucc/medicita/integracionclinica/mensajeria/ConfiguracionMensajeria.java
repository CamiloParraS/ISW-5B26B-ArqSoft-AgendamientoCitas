package co.edu.ucc.medicita.integracionclinica.mensajeria;

import java.util.ArrayList;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología del consumidor (declaración idempotente, contrato v1):
 * exchange medicita.citas, cola quorum con DLX/DLQ y cola de reintento con TTL.
 */
@Configuration
public class ConfiguracionMensajeria {

    public static final String EXCHANGE = "medicita.citas";
    public static final String DLX = "medicita.dlx";
    public static final String COLA = "integracion-clinica.citas";
    public static final String COLA_DLQ = COLA + ".dlq";
    public static final String COLA_REINTENTO = COLA + ".reintento";

    public static final String[] CLAVES = {"cita.agendada.v1", "cita.cancelada.v1", "cita.atendida.v1"};

    @Bean
    TopicExchange exchangeCitas() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange exchangeMuertos() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue colaCitas() {
        return QueueBuilder.durable(COLA).quorum()
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(COLA)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    @Bean
    Queue colaMuertos() {
        return QueueBuilder.durable(COLA_DLQ).quorum().build();
    }

    @Bean
    Queue colaReintento(@Value("${medicita.mensajeria.ttl-reintento-ms}") long ttlMs) {
        return QueueBuilder.durable(COLA_REINTENTO).quorum()
                .ttl((int) ttlMs)
                .deadLetterExchange("")
                .deadLetterRoutingKey(COLA)
                .build();
    }

    @Bean
    Binding enlaceMuertos(Queue colaMuertos, DirectExchange exchangeMuertos) {
        return BindingBuilder.bind(colaMuertos).to(exchangeMuertos).with(COLA);
    }

    @Bean
    Declarables enlacesCitas(Queue colaCitas, TopicExchange exchangeCitas) {
        var enlaces = new ArrayList<Binding>();
        for (String clave : CLAVES) {
            enlaces.add(BindingBuilder.bind(colaCitas).to(exchangeCitas).with(clave));
        }
        return new Declarables(enlaces);
    }
}
