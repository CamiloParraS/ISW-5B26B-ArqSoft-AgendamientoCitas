package co.edu.ucc.medicita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Reintento idempotente: la misma clave devuelve la misma cita y deja una sola fila en la outbox. */
class IdempotenciaIT extends BaseIT {

    @Autowired
    ObjectMapper json;

    private MockHttpServletResponse confirmar(RequestPostProcessor quien, UUID cupo, String clave) throws Exception {
        return mvc.perform(post("/api/agenda/citas").with(quien).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cupoId\":\"" + cupo + "\"}")).andReturn().getResponse();
    }

    @Test
    void laMismaClaveDevuelveLaMismaCitaYUnSoloEvento() throws Exception {
        UUID cupo = crearCupos(ROSERO, SEDE_CENTRO, 1).get(0);
        UUID sub = UUID.randomUUID();
        RequestPostProcessor quien = paciente(sub, "Paciente Idempotente", "IDEM-" + sub);

        var retencion = mvc.perform(post("/api/agenda/retenciones").with(quien).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cupoId\":\"" + cupo + "\"}")).andReturn().getResponse();
        assertThat(retencion.getStatus()).isEqualTo(201);

        String clave = UUID.randomUUID().toString();
        MockHttpServletResponse primera = confirmar(quien, cupo, clave);
        MockHttpServletResponse segunda = confirmar(quien, cupo, clave);
        assertThat(primera.getStatus()).isEqualTo(201);
        assertThat(segunda.getStatus()).isEqualTo(200);
        JsonNode a = json.readTree(primera.getContentAsString());
        JsonNode b = json.readTree(segunda.getContentAsString());
        assertThat(b.get("id").asText()).isEqualTo(a.get("id").asText());
        assertThat(a.get("estado").asText()).isEqualTo("CONFIRMADA");
        assertThat(a.get("medicoNombre").asText()).isEqualTo("Carolina Rosero");

        assertThat(jdbc.sql("SELECT count(*) FROM agenda.cita WHERE cupo_id = :c").param("c", cupo).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM agenda.outbox WHERE tipo = 'CitaAgendada' AND carga->'datos'->>'citaId' = :c")
                .param("c", a.get("id").asText()).query(Long.class).single()).isEqualTo(1);

        // Sin el encabezado la confirmación se rechaza
        var sinClave = mvc.perform(post("/api/agenda/citas").with(quien).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cupoId\":\"" + cupo + "\"}")).andReturn().getResponse();
        assertThat(sinClave.getStatus()).isEqualTo(400);
        assertThat(sinClave.getContentAsString()).contains("CLAVE_IDEMPOTENCIA_REQUERIDA");
    }

    @Test
    void reintentosConcurrentesConLaMismaClaveCreanUnaSolaCita() throws Exception {
        UUID cupo = crearCupos(ROSERO, SEDE_CENTRO, 1).get(0);
        UUID sub = UUID.randomUUID();
        RequestPostProcessor quien = paciente(sub, "Paciente Reintento", "REINT-" + sub);
        mvc.perform(post("/api/agenda/retenciones").with(quien).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cupoId\":\"" + cupo + "\"}")).andReturn();

        String clave = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<MockHttpServletResponse>> futuros = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futuros.add(pool.submit(() -> confirmar(quien, cupo, clave)));
        }
        int nuevas = 0;
        int repeticiones = 0;
        String citaId = null;
        for (var f : futuros) {
            MockHttpServletResponse r = f.get();
            String id = json.readTree(r.getContentAsString()).get("id").asText();
            citaId = citaId == null ? id : citaId;
            assertThat(id).isEqualTo(citaId);
            if (r.getStatus() == 201) {
                nuevas++;
            } else {
                assertThat(r.getStatus()).isEqualTo(200);
                repeticiones++;
            }
        }
        pool.shutdown();
        assertThat(nuevas).isEqualTo(1);
        assertThat(repeticiones).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM agenda.outbox WHERE carga->'datos'->>'citaId' = :c")
                .param("c", citaId).query(Long.class).single()).isEqualTo(1);
    }
}
