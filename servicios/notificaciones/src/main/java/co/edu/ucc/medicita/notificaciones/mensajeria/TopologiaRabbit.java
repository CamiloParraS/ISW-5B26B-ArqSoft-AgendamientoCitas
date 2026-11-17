package co.edu.ucc.medicita.notificaciones.mensajeria;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topología del consumidor (contrato de eventos v1, sección 6): cola quorum con DLX, DLQ y cola de reintento. */
@Configuration
public class TopologiaRabbit {

    public static final String EXCHANGE = "medicita.citas";
    public static final String DLX = "medicita.dlx";
    public static final String COLA = "notificaciones.citas";
    public static final String COLA_DLQ = COLA + ".dlq";
    public static final String COLA_REINTENTO = COLA + ".reintento";

    @Bean
    public Declarables topologiaNotificaciones() {
        TopicExchange citas = new TopicExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DLX, true, false);

        Queue cola = QueueBuilder.durable(COLA).quorum()
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", COLA)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .build();
        Queue dlq = QueueBuilder.durable(COLA_DLQ).quorum().build();
        Queue reintento = QueueBuilder.durable(COLA_REINTENTO).quorum()
                .withArgument("x-message-ttl", 30000)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", COLA)
                .build();

        Binding agendada = BindingBuilder.bind(cola).to(citas).with("cita.agendada.v1");
        Binding cancelada = BindingBuilder.bind(cola).to(citas).with("cita.cancelada.v1");
        Binding aDlq = BindingBuilder.bind(dlq).to(dlx).with(COLA);

        return new Declarables(citas, dlx, cola, dlq, reintento, agendada, cancelada, aDlq);
    }
}
