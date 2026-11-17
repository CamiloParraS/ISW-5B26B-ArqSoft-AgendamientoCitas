package co.edu.ucc.medicita.integracionclinica.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prueba de integración: RabbitMQ real (Testcontainers) + HCE FHIR falsa en memoria.
 * Demuestra el flujo completo y la degradación elegante.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.circuitbreaker.instances.hce.wait-duration-in-open-state=2s",
        "medicita.mensajeria.ttl-reintento-ms=2000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegracionClinicaIT {

    private static final String PACIENTE_ANA = "a0000000-0000-4000-8000-000000000001";
    private static final String MEDICO = "b0000000-0000-4000-8000-000000000001";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8-management");

    static HceFalsa hce;

    @BeforeAll
    static void iniciarHce() throws Exception {
        hce = new HceFalsa();
        Patient ana = new Patient();
        ana.addIdentifier().setSystem("urn:medicita:paciente").setValue(PACIENTE_ANA);
        ana.addName().setFamily("Gómez").addGiven("Ana");
        ana.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1950-03-14"));
        hce.precargar(ana);
    }

    @AfterAll
    static void detenerHce() {
        hce.close();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.rabbitmq.addresses", () -> RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        registro.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registro.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registro.add("medicita.hce.fhir-url", () -> hce.url());
    }

    @Autowired
    RabbitTemplate plantilla;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    TestRestTemplate http;

    @Test
    @Order(1)
    void citaAgendadaCreaAppointmentBookedEnLaHce() {
        String citaId = UUID.randomUUID().toString();
        publicar("CitaAgendada", "cita.agendada.v1", agendada(citaId), "corr-1");

        await().atMost(Duration.ofSeconds(30)).until(() -> cita(citaId) != null);
        Appointment a = cita(citaId);
        assertThat(a.getStatus()).isEqualTo(Appointment.AppointmentStatus.BOOKED);
        assertThat(a.getServiceTypeFirstRep().getText()).isEqualTo("Medicina general");
        assertThat(hce.correlaciones).contains("corr-1");
        // el paciente ya existía: no se crea otro
        assertThat(hce.de("Patient")).hasSize(1);
        assertThat(http.getForObject("/interno/hce/estado", String.class)).contains("CLOSED");
    }

    @Test
    @Order(2)
    void hceCaidaReintentaAbreElBreakerYAlVolverCreaCadaCitaUnaSolaVez() {
        hce.caida(true);
        List<String> citas = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = UUID.randomUUID().toString();
            citas.add(id);
            publicar("CitaAgendada", "cita.agendada.v1", agendada(id), "corr-caida-" + i);
        }
        // un duplicado exacto (entrega "al menos una vez")
        publicar("CitaAgendada", "cita.agendada.v1", agendada(citas.get(0)), "corr-dup");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(http.getForObject("/interno/hce/estado", String.class)).contains("OPEN"));
        await().atMost(Duration.ofSeconds(30)).until(() -> mensajesEnReintento() > 0);
        assertThat(citas).allMatch(id -> cita(id) == null);

        hce.caida(false);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(citas).allMatch(id -> cita(id) != null));
        // exactamente una vez por cita, aunque hubo reintentos y un duplicado
        for (String id : citas) {
            assertThat(hce.de("Appointment").stream().filter(r -> tieneCita((Appointment) r, id))).hasSize(1);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(http.getForObject("/interno/hce/estado", String.class)).contains("CLOSED"));
    }

    @Test
    @Order(3)
    void citaAtendidaDejaEncounterFinishedYAppointmentFulfilled() {
        String citaId = UUID.randomUUID().toString();
        publicar("CitaAgendada", "cita.agendada.v1", agendada(citaId), "corr-a");
        await().atMost(Duration.ofSeconds(30)).until(() -> cita(citaId) != null);

        publicar("CitaAtendida", "cita.atendida.v1", atendida(citaId), "corr-b");

        await().atMost(Duration.ofSeconds(30)).until(() -> encuentro(citaId) != null);
        Encounter e = encuentro(citaId);
        assertThat(e.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
        assertThat(cita(citaId).getStatus()).isEqualTo(Appointment.AppointmentStatus.FULFILLED);
        assertThat(e.getAppointmentFirstRep().getReference()).isEqualTo("Appointment/" + cita(citaId).getIdElement().getIdPart());

        // duplicado: sigue habiendo un solo Encounter
        publicar("CitaAtendida", "cita.atendida.v1", atendida(citaId), "corr-c");
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(() ->
                hce.de("Encounter").stream().filter(r -> tieneCita((Encounter) r, citaId)).count() == 1);
    }

    @Test
    @Order(4)
    void jsonMalformadoVaDirectoALaDlq() {
        plantilla.send("medicita.citas", "cita.agendada.v1", MessageBuilder.withBody("{esto no es json".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
        await().atMost(Duration.ofSeconds(20)).until(() ->
                admin.getQueueInfo(ConfiguracionMensajeria.COLA_DLQ).getMessageCount() >= 1);
    }

    private int mensajesEnReintento() {
        var info = admin.getQueueInfo(ConfiguracionMensajeria.COLA_REINTENTO);
        return info == null ? 0 : info.getMessageCount();
    }

    private Appointment cita(String citaId) {
        return hce.de("Appointment").stream().map(r -> (Appointment) r)
                .filter(a -> tieneCita(a, citaId)).findFirst().orElse(null);
    }

    private Encounter encuentro(String citaId) {
        return hce.de("Encounter").stream().map(r -> (Encounter) r)
                .filter(e -> tieneCita(e, citaId)).findFirst().orElse(null);
    }

    private static boolean tieneCita(Resource r, String citaId) {
        var ids = r instanceof Appointment a ? a.getIdentifier() : ((Encounter) r).getIdentifier();
        return ids.stream().anyMatch(i -> "urn:medicita:cita".equals(i.getSystem()) && citaId.equals(i.getValue()));
    }

    private void publicar(String tipo, String clave, String json, String correlacion) {
        Message m = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setType(tipo)
                .setMessageId(UUID.randomUUID().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-correlation-id", correlacion)
                .build();
        plantilla.send("medicita.citas", clave, m);
    }

    private static String agendada(String citaId) {
        return """
                {"eventoId":"%s","tipo":"CitaAgendada","version":1,"ocurridoEn":"2026-11-17T12:00:00-05:00","correlacionId":"x",
                 "datos":{"citaId":"%s","pacienteId":"%s","medicoId":"%s","medicoNombre":"Carolina Rosero",
                  "especialidad":"Medicina general","sedeId":"c0000000-0000-4000-8000-000000000001","sedeNombre":"Sede Centro",
                  "inicio":"2026-11-18T07:00:00-05:00","fin":"2026-11-18T07:20:00-05:00"}}
                """.formatted(UUID.randomUUID(), citaId, PACIENTE_ANA, MEDICO);
    }

    private static String atendida(String citaId) {
        return """
                {"eventoId":"%s","tipo":"CitaAtendida","version":1,"ocurridoEn":"2026-11-18T07:25:00-05:00","correlacionId":"x",
                 "datos":{"citaId":"%s","pacienteId":"%s","medicoId":"%s","medicoNombre":"Carolina Rosero",
                  "especialidad":"Medicina general","sedeNombre":"Sede Centro",
                  "inicio":"2026-11-18T07:00:00-05:00","fin":"2026-11-18T07:20:00-05:00","atendidaEn":"2026-11-18T07:25:00-05:00"}}
                """.formatted(UUID.randomUUID(), citaId, PACIENTE_ANA, MEDICO);
    }
}
