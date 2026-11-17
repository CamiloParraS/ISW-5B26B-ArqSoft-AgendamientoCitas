package co.edu.ucc.medicita.agenda.interno;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relé de la outbox: cada 500 ms toma hasta 100 eventos pendientes (FOR UPDATE SKIP LOCKED, seguro con 2
 * instancias) y los publica en RabbitMQ con confirmaciones del publicador y mandatory. Si el broker falla, el
 * evento queda pendiente y se incrementa "intentos": jamás se pierde ni bloquea la reserva.
 */
@Component
public class ReleOutbox {

    private static final Logger log = LoggerFactory.getLogger(ReleOutbox.class);
    private static final long ESPERA_CONFIRMACION_SEGUNDOS = 5;

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;

    ReleOutbox(JdbcClient jdbc, RabbitTemplate rabbit, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = new TransactionTemplate(tm);
        // mandatory: un mensaje no enrutable regresa y se trata como no publicado
        this.rabbit.setReturnsCallback(r -> log.warn("Mensaje no enrutable devuelto por el broker: {}", r.getRoutingKey()));
    }

    @Scheduled(fixedDelay = 500)
    public void ciclo() {
        try {
            publicarPendientes();
        } catch (Exception e) {
            log.warn("Relé de outbox: ciclo fallido, se reintentará: {}", e.toString());
        }
    }

    /** Publica un lote y devuelve cuántos eventos quedaron publicados. */
    public int publicarPendientes() {
        Integer publicados = tx.execute(estado -> {
            List<Pendiente> lote = jdbc.sql("""
                            SELECT id, tipo, routing_key, carga::text AS carga, correlacion_id
                              FROM agenda.outbox WHERE publicado_en IS NULL
                             ORDER BY creado_en LIMIT 100 FOR UPDATE SKIP LOCKED""")
                    .query((rs, n) -> new Pendiente(rs.getObject("id", UUID.class), rs.getString("tipo"),
                            rs.getString("routing_key"), rs.getString("carga"), rs.getString("correlacion_id")))
                    .list();
            int ok = 0;
            for (Pendiente p : lote) {
                MDC.put("correlacionId", p.correlacionId());
                try {
                    enviar(p);
                    jdbc.sql("UPDATE agenda.outbox SET publicado_en = now() WHERE id = :id").param("id", p.id()).update();
                    log.info("Outbox: evento {} ({}) publicado en {}", p.id(), p.tipo(), p.routingKey());
                    ok++;
                } catch (Exception e) {
                    jdbc.sql("UPDATE agenda.outbox SET intentos = intentos + 1 WHERE id = :id").param("id", p.id()).update();
                    log.warn("Outbox: no se pudo publicar {} ({}), queda pendiente: {}", p.id(), p.tipo(), e.toString());
                    if (!(e instanceof NoEnrutable)) {
                        // broker caído o sin confirmar: todo el lote restante cuenta un intento y no se insiste
                        List<UUID> restantes = lote.subList(lote.indexOf(p) + 1, lote.size()).stream().map(Pendiente::id).toList();
                        for (UUID id : restantes) {
                            jdbc.sql("UPDATE agenda.outbox SET intentos = intentos + 1 WHERE id = :id").param("id", id).update();
                        }
                        break;
                    }
                    // un mensaje sin cola enlazada no debe bloquear a los demás eventos
                } finally {
                    MDC.remove("correlacionId");
                }
            }
            return ok;
        });
        return publicados == null ? 0 : publicados;
    }

    private void enviar(Pendiente p) throws Exception {
        Message mensaje = MessageBuilder.withBody(p.carga().getBytes(StandardCharsets.UTF_8))
                .setMessageId(p.id().toString())
                .setType(p.tipo())
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-correlation-id", p.correlacionId())
                .build();
        CorrelationData datos = new CorrelationData(p.id().toString());
        rabbit.send(Outbox.EXCHANGE, p.routingKey(), mensaje, datos);
        CorrelationData.Confirm confirmacion = datos.getFuture().get(ESPERA_CONFIRMACION_SEGUNDOS, TimeUnit.SECONDS);
        if (!confirmacion.isAck()) {
            throw new IllegalStateException("El broker no confirmó el mensaje: " + confirmacion.getReason());
        }
        if (datos.getReturned() != null) {
            throw new NoEnrutable();
        }
    }

    /** El broker confirmó pero devolvió el mensaje (mandatory): no hay cola enlazada a la routing key. */
    private static final class NoEnrutable extends IllegalStateException {
        NoEnrutable() {
            super("El broker no pudo enrutar el mensaje (sin cola enlazada)");
        }
    }

    private record Pendiente(UUID id, String tipo, String routingKey, String carga, String correlacionId) {
    }

    /** Exchange de eventos de citas: topic, durable. Los consumidores lo declaran también (idempotente). */
    @Configuration
    static class ExchangeConfig {

        @Bean
        TopicExchange exchangeCitas() {
            return ExchangeBuilder.topicExchange(Outbox.EXCHANGE).durable(true).build();
        }
    }
}
