package co.edu.ucc.medicita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Broker caído: la reserva sigue funcionando, el evento queda pendiente y se publica al volver el broker. */
class OutboxIT extends BaseIT {

    @Autowired
    RabbitAdmin admin;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ObjectMapper json;

    @Test
    void conElBrokerCaidoLaReservaFuncionaYElEventoSePublicaAlVolver() throws Exception {
        UUID cupo = crearCupos(ROSERO, SEDE_CENTRO, 1).get(0);
        UUID sub = UUID.randomUUID();
        var quien = paciente(sub, "Paciente Outbox", "OUTBOX-" + sub);
        String correlacion = "traza-" + UUID.randomUUID();

        RABBIT.stop();
        try {
            var retencion = mvc.perform(post("/api/agenda/retenciones").with(quien).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cupoId\":\"" + cupo + "\"}")).andReturn().getResponse();
            assertThat(retencion.getStatus()).isEqualTo(201);
            var confirmacion = mvc.perform(post("/api/agenda/citas").with(quien).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Correlation-Id", correlacion).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cupoId\":\"" + cupo + "\"}")).andReturn().getResponse();
            assertThat(confirmacion.getStatus()).as("la reserva no depende del broker").isEqualTo(201);
            assertThat(confirmacion.getHeader("X-Correlation-Id")).isEqualTo(correlacion);
            String citaId = json.readTree(confirmacion.getContentAsString()).get("id").asText();

            // El evento queda pendiente y el relé acumula intentos fallidos
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var fila = jdbc.sql("SELECT publicado_en IS NULL AS pendiente, intentos, correlacion_id FROM agenda.outbox "
                                + "WHERE carga->'datos'->>'citaId' = :c").param("c", citaId)
                        .query((rs, n) -> new Object[]{rs.getBoolean("pendiente"), rs.getInt("intentos"),
                                rs.getString("correlacion_id")}).single();
                assertThat((Boolean) fila[0]).isTrue();
                assertThat((Integer) fila[1]).isGreaterThan(0);
                assertThat(fila[2]).isEqualTo(correlacion);
            });

            // Vuelve el broker y se declara una cola de prueba enlazada al exchange
            RABBIT.start();
            // el broker reinicia con otro puerto publicado: se apunta la fábrica de conexiones al nuevo
            ((CachingConnectionFactory) admin.getRabbitTemplate().getConnectionFactory())
                    .setAddresses(RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
            Queue cola = new Queue("it.outbox." + UUID.randomUUID(), false, false, true);
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                admin.declareQueue(cola);
                admin.declareBinding(new Binding(cola.getName(), Binding.DestinationType.QUEUE, "medicita.citas",
                        "cita.agendada.v1", null));
                return true;
            });

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                    jdbc.sql("SELECT publicado_en IS NOT NULL FROM agenda.outbox WHERE carga->'datos'->>'citaId' = :c")
                            .param("c", citaId).query(Boolean.class).single()).isTrue());

            // la cola también recibe eventos pendientes de otras pruebas: se busca el de esta cita
            java.util.concurrent.atomic.AtomicReference<Message> hallado = new java.util.concurrent.atomic.AtomicReference<>();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() -> {
                Message candidato;
                while ((candidato = rabbit.receive(cola.getName())) != null) {
                    JsonNode n = json.readTree(candidato.getBody());
                    if (citaId.equals(n.path("datos").path("citaId").asText())) {
                        hallado.set(candidato);
                        return true;
                    }
                }
                return false;
            });
            Message m = hallado.get();
            JsonNode sobre = json.readTree(m.getBody());
            assertThat(m).as("mensaje publicado tras volver el broker").isNotNull();
            assertThat(sobre.get("tipo").asText()).isEqualTo("CitaAgendada");
            assertThat(sobre.get("version").asInt()).isEqualTo(1);
            assertThat(sobre.get("datos").get("citaId").asText()).isEqualTo(citaId);
            assertThat(m.getMessageProperties().getMessageId()).isEqualTo(sobre.get("eventoId").asText());
            assertThat(m.getMessageProperties().getType()).isEqualTo("CitaAgendada");
            assertThat(m.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(m.getMessageProperties().getHeader("x-correlation-id").toString()).isEqualTo(correlacion);
        } finally {
            if (!RABBIT.isRunning()) {
                RABBIT.start();
                ((CachingConnectionFactory) admin.getRabbitTemplate().getConnectionFactory())
                        .setAddresses(RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
            }
        }
    }
}
