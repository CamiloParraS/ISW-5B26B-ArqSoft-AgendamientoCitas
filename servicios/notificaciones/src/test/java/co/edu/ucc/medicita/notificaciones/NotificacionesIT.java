package co.edu.ucc.medicita.notificaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Prueba de integración con Postgres y RabbitMQ reales (Testcontainers), un servidor HTTP que simula
 * WhatsApp y SMS, y GreenMail como servidor SMTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificacionesIT {

    static final UUID ANA = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID LUIS = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    static final UUID CARGA01 = UUID.fromString("a0000000-0000-4000-8000-000000000101");
    static final UUID SOLO_TELEFONO = UUID.fromString("a0000000-0000-4000-8000-0000000009ff");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.16-alpine3.24")
            .withDatabaseName("notificaciones").withUsername("notificaciones").withPassword("notificaciones");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8-management");

    @RegisterExtension
    static final GreenMailExtension CORREO = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

    // Simulador mínimo de WhatsApp y SMS
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicBoolean WHATSAPP_RECHAZA = new AtomicBoolean(false);
    static final AtomicBoolean SMS_CAIDO = new AtomicBoolean(false);
    static final List<Map<String, Object>> PETICIONES_WHATSAPP = new CopyOnWriteArrayList<>();
    static final List<Map<String, Object>> PETICIONES_SMS = new CopyOnWriteArrayList<>();
    static final HttpServer EXTERNOS = iniciarExternos();

    static HttpServer iniciarExternos() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/whatsapp/v21.0/100200300/messages", ex -> {
                byte[] cuerpo = ex.getRequestBody().readAllBytes();
                PETICIONES_WHATSAPP.add(Map.of(
                        "authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                        "correlacion", String.valueOf(ex.getRequestHeaders().getFirst("X-Correlation-Id")),
                        "cuerpo", JSON.readTree(cuerpo)));
                if (WHATSAPP_RECHAZA.get()) {
                    responder(ex, 400, "{\"error\":{\"message\":\"Template paused\",\"type\":\"OAuthException\",\"code\":132015}}");
                } else {
                    responder(ex, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"+573001110001\",\"wa_id\":\"573001110001\"}],\"messages\":[{\"id\":\"wamid.TEST\"}]}");
                }
            });
            s.createContext("/sms/v1/mensajes", ex -> {
                byte[] cuerpo = ex.getRequestBody().readAllBytes();
                PETICIONES_SMS.add(Map.of(
                        "correlacion", String.valueOf(ex.getRequestHeaders().getFirst("X-Correlation-Id")),
                        "cuerpo", JSON.readTree(cuerpo)));
                if (SMS_CAIDO.get()) {
                    responder(ex, 503, "{\"error\":\"caida\"}");
                } else {
                    responder(ex, 201, "{\"id\":\"sms-" + UUID.randomUUID() + "\",\"estado\":\"ENVIADO\"}");
                }
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void responder(com.sun.net.httpserver.HttpExchange ex, int codigo, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(codigo, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.rabbitmq.addresses", () -> RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("spring.mail.host", () -> "127.0.0.1");
        r.add("spring.mail.port", () -> CORREO.getSmtp().getPort());
        int puerto = EXTERNOS.getAddress().getPort();
        r.add("medicita.notificaciones.canales.whatsapp.url", () -> "http://127.0.0.1:" + puerto + "/whatsapp");
        r.add("medicita.notificaciones.canales.sms.url", () -> "http://127.0.0.1:" + puerto + "/sms");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void restablecer() {
        WHATSAPP_RECHAZA.set(false);
        SMS_CAIDO.set(false);
    }

    // ---- utilidades ----

    static String evento(UUID eventoId, String tipo, UUID citaId, UUID pacienteId, String correlacion) {
        return """
                {"eventoId":"%s","tipo":"%s","version":1,"ocurridoEn":"2026-11-17T12:00:00-05:00",
                 "correlacionId":"%s","datos":{"citaId":"%s","pacienteId":"%s","medicoId":"b0000000-0000-4000-8000-000000000001",
                 "medicoNombre":"Carolina Rosero","especialidad":"Medicina general",
                 "sedeId":"c0000000-0000-4000-8000-000000000001","sedeNombre":"Sede Centro",
                 "inicio":"2026-11-18T07:00:00-05:00","fin":"2026-11-18T07:20:00-05:00"}}
                """.formatted(eventoId, tipo, correlacion, citaId, pacienteId);
    }

    void publicar(String rk, String tipo, UUID eventoId, String json, String correlacion) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(eventoId.toString());
        p.setType(tipo);
        p.setContentType("application/json");
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        p.setHeader("x-correlation-id", correlacion);
        rabbit.send("medicita.citas", rk, new Message(json.getBytes(StandardCharsets.UTF_8), p));
    }

    void publicarAgendada(UUID eventoId, UUID citaId, UUID paciente, String correlacion) {
        publicar("cita.agendada.v1", "CitaAgendada", eventoId, evento(eventoId, "CitaAgendada", citaId, paciente, correlacion), correlacion);
    }

    List<Map<String, Object>> envios(UUID citaId) {
        return jdbc.queryForList("SELECT canal, estado, tipo, id_externo, correlacion_id, detalle FROM notificaciones.envio WHERE cita_id = ? ORDER BY creado_en, id", citaId);
    }

    // ---- pruebas ----

    @Test
    @Order(1)
    void citaAgendadaSeEnviaPorWhatsAppYSeGuardaParaRecordar() {
        UUID citaId = UUID.randomUUID();
        publicarAgendada(UUID.randomUUID(), citaId, ANA, "corr-it-1");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(1));

        Map<String, Object> envio = envios(citaId).get(0);
        assertThat(envio).containsEntry("canal", "WHATSAPP").containsEntry("estado", "ENVIADO")
                .containsEntry("tipo", "CONFIRMACION").containsEntry("id_externo", "wamid.TEST")
                .containsEntry("correlacion_id", "corr-it-1");

        Map<String, Object> peticion = PETICIONES_WHATSAPP.get(PETICIONES_WHATSAPP.size() - 1);
        assertThat(peticion).containsEntry("authorization", "Bearer token-demo").containsEntry("correlacion", "corr-it-1");
        JsonNode cuerpo = (JsonNode) peticion.get("cuerpo");
        assertThat(cuerpo.path("messaging_product").asText()).isEqualTo("whatsapp");
        assertThat(cuerpo.path("to").asText()).isEqualTo("+573001110001");
        assertThat(cuerpo.path("type").asText()).isEqualTo("template");
        assertThat(cuerpo.path("template").path("name").asText()).isEqualTo("cita_confirmada");
        assertThat(cuerpo.path("template").path("language").path("code").asText()).isEqualTo("es_CO");
        JsonNode parametros = cuerpo.path("template").path("components").path(0).path("parameters");
        assertThat(cuerpo.path("template").path("components").path(0).path("type").asText()).isEqualTo("body");
        assertThat(parametros.findValuesAsText("text"))
                .containsExactly("Carolina Rosero", "miércoles 18 de noviembre", "07:00", "Sede Centro");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificaciones.cita_por_recordar WHERE cita_id = ?",
                Integer.class, citaId)).isEqualTo(1);

        // el endpoint interno los lista
        JsonNode listado = http.getForObject("/interno/envios?citaId={c}", JsonNode.class, citaId);
        assertThat(listado).hasSize(1);
        assertThat(listado.get(0).path("canal").asText()).isEqualTo("WHATSAPP");
    }

    @Test
    @Order(2)
    void plantillaRechazadaPorWhatsAppCaeASmsConAmbosIntentosRegistrados() {
        WHATSAPP_RECHAZA.set(true);
        UUID citaId = UUID.randomUUID();
        publicarAgendada(UUID.randomUUID(), citaId, ANA, "corr-it-2");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(2));

        List<Map<String, Object>> filas = envios(citaId);
        assertThat(filas.get(0)).containsEntry("canal", "WHATSAPP").containsEntry("estado", "RECHAZADO");
        assertThat((String) filas.get(0).get("detalle")).contains("132015");
        assertThat(filas.get(1)).containsEntry("canal", "SMS").containsEntry("estado", "ENVIADO");

        Map<String, Object> sms = PETICIONES_SMS.get(PETICIONES_SMS.size() - 1);
        assertThat(sms).containsEntry("correlacion", "corr-it-2");
        JsonNode cuerpo = (JsonNode) sms.get("cuerpo");
        assertThat(cuerpo.path("para").asText()).isEqualTo("+573001110001");
        assertThat(cuerpo.path("texto").asText())
                .isEqualTo("MediCita: su cita con Carolina Rosero quedó confirmada para el miércoles 18 de noviembre, 07:00, en Sede Centro.");
    }

    @Test
    @Order(3)
    void eventoDuplicadoNoGeneraUnSegundoEnvio() {
        UUID eventoId = UUID.randomUUID();
        UUID citaId = UUID.randomUUID();
        publicarAgendada(eventoId, citaId, ANA, "corr-it-3");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(1));

        publicarAgendada(eventoId, citaId, ANA, "corr-it-3"); // mismo eventoId (entrega "al menos una vez")
        // centinela: cuando termina, el duplicado ya fue consumido
        UUID centinela = UUID.randomUUID();
        publicarAgendada(UUID.randomUUID(), centinela, ANA, "corr-it-3b");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(centinela)).hasSize(1));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(envios(citaId)).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificaciones.evento_procesado WHERE evento_id = ?",
                Integer.class, eventoId)).isEqualTo(1);
    }

    @Test
    @Order(4)
    void citaCanceladaAvisaYBorraElRecordatorio() {
        UUID citaId = UUID.randomUUID();
        publicarAgendada(UUID.randomUUID(), citaId, LUIS, "corr-it-4");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(1));
        assertThat(envios(citaId).get(0)).containsEntry("canal", "SMS").containsEntry("tipo", "CONFIRMACION");

        UUID eventoId = UUID.randomUUID();
        publicar("cita.cancelada.v1", "CitaCancelada", eventoId, evento(eventoId, "CitaCancelada", citaId, LUIS, "corr-it-4"), "corr-it-4");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(2));
        assertThat(envios(citaId).get(1)).containsEntry("tipo", "CANCELACION").containsEntry("estado", "ENVIADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificaciones.cita_por_recordar WHERE cita_id = ?",
                Integer.class, citaId)).isZero();
    }

    @Test
    @Order(5)
    void pacienteConCorreoComoPreferidoRecibeEmailEnSmtp() {
        UUID citaId = UUID.randomUUID();
        publicarAgendada(UUID.randomUUID(), citaId, CARGA01, "corr-it-5");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(1));
        assertThat(envios(citaId).get(0)).containsEntry("canal", "CORREO").containsEntry("estado", "ENVIADO");

        await().atMost(Duration.ofSeconds(10)).until(() -> CORREO.getReceivedMessages().length >= 1);
        MimeMessage[] recibidos = CORREO.getReceivedMessages();
        MimeMessage ultimo = recibidos[recibidos.length - 1];
        try {
            assertThat(ultimo.getAllRecipients()[0].toString()).isEqualTo("carga01@medicita.test");
            assertThat(ultimo.getSubject()).isEqualTo("Cita confirmada con Carolina Rosero");
            assertThat(ultimo.getHeader("X-Correlation-Id")[0]).isEqualTo("corr-it-5");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Order(6)
    void eventoMalFormadoVaDirectoALaDlq() {
        int antes = mensajes("notificaciones.citas.dlq");
        publicar("cita.agendada.v1", "CitaAgendada", UUID.randomUUID(), "esto no es json", "corr-it-6");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(mensajes("notificaciones.citas.dlq")).isEqualTo(antes + 1));
    }

    @Test
    @Order(7)
    void siTodosLosCanalesFallanVaALaColaDeReintentoConIntentosYSinMarcarElEventoProcesado() {
        jdbc.update("INSERT INTO notificaciones.destinatario (paciente_id, nombre, telefono, email, canal_preferido) VALUES (?, 'Sin correo', '+573009999999', NULL, 'SMS')",
                SOLO_TELEFONO);
        WHATSAPP_RECHAZA.set(true);
        SMS_CAIDO.set(true);
        UUID eventoId = UUID.randomUUID();
        UUID citaId = UUID.randomUUID();
        int antes = mensajes("notificaciones.citas.reintento");
        publicarAgendada(eventoId, citaId, SOLO_TELEFONO, "corr-it-7");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(envios(citaId)).hasSize(2));
        assertThat(envios(citaId)).extracting(f -> f.get("estado")).containsExactly("FALLIDO", "RECHAZADO");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mensajes("notificaciones.citas.reintento")).isEqualTo(antes + 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificaciones.evento_procesado WHERE evento_id = ?",
                Integer.class, eventoId)).isZero();
    }

    private int mensajes(String cola) {
        var info = admin.getQueueInfo(cola);
        return info == null ? 0 : info.getMessageCount();
    }
}
