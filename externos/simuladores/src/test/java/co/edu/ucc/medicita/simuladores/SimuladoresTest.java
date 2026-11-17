package co.edu.ucc.medicita.simuladores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimuladoresTest {

    private static final String CITA_JSON = """
            {"resourceType":"Appointment","status":"booked",
             "identifier":[{"system":"urn:medicita:cita","value":"cita-1"}],
             "participant":[{"actor":{"reference":"Patient/a0000000-0000-4000-8000-000000000001"},"status":"accepted"}]}
            """;

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    @AfterEach
    void restaurar() {
        json("/control/hce", "{\"caida\":false}");
        json("/control/whatsapp/plantillas/cita_confirmada", "{\"rechazada\":false}");
        json("/control/sms", "{\"caida\":false}");
    }

    @Test
    void plantillaRechazadaDevuelve400ConCodigo132015() {
        json("/control/whatsapp/plantillas/cita_confirmada", "{\"rechazada\":true}");

        ResponseEntity<String> r = whatsapp("cita_confirmada", "template");

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("\"code\":132015").contains("Template paused");
    }

    @Test
    void plantillaAprobadaDevuelveLaFormaDeMeta() {
        ResponseEntity<String> r = whatsapp("cita_confirmada", "template");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"messaging_product\":\"whatsapp\"")
                .contains("\"wa_id\":\"573001110001\"").contains("wamid.");
    }

    @Test
    void mensajeQueNoEsPlantillaDevuelve131047() {
        ResponseEntity<String> r = whatsapp("cita_confirmada", "text");

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("\"code\":131047");
    }

    @Test
    void smsSeEnviaYSeConsulta() {
        ResponseEntity<String> r = json("/sms/v1/mensajes", "{\"para\":\"+573001110002\",\"texto\":\"hola\"}");
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getBody()).contains("\"estado\":\"ENVIADO\"");
        String id = r.getBody().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(http.getForEntity("/sms/v1/mensajes/" + id, String.class).getStatusCode().value()).isEqualTo(200);

        json("/control/sms", "{\"caida\":true}");
        assertThat(json("/sms/v1/mensajes", "{\"para\":\"+57\",\"texto\":\"x\"}").getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void hceCaidaResponde503YAlRestaurarVuelveA200() {
        json("/control/hce", "{\"caida\":true}");
        assertThat(http.getForEntity("/fhir/Patient", String.class).getStatusCode().value()).isEqualTo(503);

        json("/control/hce", "{\"caida\":false}");
        assertThat(http.getForEntity("/fhir/metadata", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void pacientesPrecargadosSeBuscanPorIdentificador() {
        ResponseEntity<String> r = http.getForEntity(URI.create(base()
                + "/fhir/Patient?identifier=urn:medicita:paciente%7Ca0000000-0000-4000-8000-000000000001"), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"total\"").contains("Gómez").contains("1950-03-14");
    }

    @Test
    void createCondicionalDosVecesDejaUnSoloRecurso() {
        HttpHeaders cabeceras = fhir();
        cabeceras.add("If-None-Exist", "identifier=urn:medicita:cita%7Ccita-1");
        cabeceras.add("X-Correlation-Id", "corr-hce-1");

        ResponseEntity<String> primero = http.exchange(base() + "/fhir/Appointment", HttpMethod.POST,
                new HttpEntity<>(CITA_JSON, cabeceras), String.class);
        ResponseEntity<String> segundo = http.exchange(base() + "/fhir/Appointment", HttpMethod.POST,
                new HttpEntity<>(CITA_JSON, cabeceras), String.class);

        assertThat(primero.getStatusCode().value()).isEqualTo(201);
        assertThat(segundo.getStatusCode().value()).isEqualTo(200);
        assertThat(segundo.getHeaders().getFirst("Location")).isEqualTo(primero.getHeaders().getFirst("Location"));
        String busqueda = http.getForObject(URI.create(base()
                + "/fhir/Appointment?identifier=urn:medicita:cita%7Ccita-1"), String.class);
        assertThat(busqueda).contains("\"total\":1");

        String registro = http.getForObject("/registro/hce", String.class);
        assertThat(registro).contains("corr-hce-1").contains("Appointment");
    }

    @Test
    void updateCondicionalCambiaElEstadoSinDuplicar() {
        HttpHeaders cabeceras = fhir();
        http.exchange(base() + "/fhir/Appointment", HttpMethod.POST,
                new HttpEntity<>(CITA_JSON.replace("cita-1", "cita-2"), cabeceras), String.class);

        ResponseEntity<String> r = http.exchange(URI.create(base()
                        + "/fhir/Appointment?identifier=urn:medicita:cita%7Ccita-2"), HttpMethod.PUT,
                new HttpEntity<>(CITA_JSON.replace("cita-1", "cita-2").replace("booked", "cancelled"), cabeceras), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        String busqueda = http.getForObject(URI.create(base()
                + "/fhir/Appointment?identifier=urn:medicita:cita%7Ccita-2"), String.class);
        assertThat(busqueda).contains("\"total\":1").contains("cancelled");
    }

    @Test
    void elPanelSeSirveEnLaRaiz() {
        ResponseEntity<String> r = http.getForEntity("/", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("Simuladores externos de MediCita");
    }

    private String base() {
        return "http://localhost:" + puerto;
    }

    private HttpHeaders fhir() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/fhir+json"));
        h.setAccept(java.util.List.of(MediaType.parseMediaType("application/fhir+json")));
        return h;
    }

    private ResponseEntity<String> json(String ruta, String cuerpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(ruta, new HttpEntity<>(cuerpo, h), String.class);
    }

    private ResponseEntity<String> whatsapp(String plantilla, String tipo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth("token-demo");
        h.add("X-Correlation-Id", "corr-wa-1");
        String cuerpo = """
                {"messaging_product":"whatsapp","to":"+573001110001","type":"%s",
                 "template":{"name":"%s","language":{"code":"es_CO"},
                  "components":[{"type":"body","parameters":[{"type":"text","text":"Carolina Rosero"}]}]}}
                """.formatted(tipo, plantilla);
        return http.postForEntity("/whatsapp/v21.0/100200300/messages", new HttpEntity<>(cuerpo, h), String.class);
    }
}
