package co.edu.ucc.medicita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** E2: 50 pacientes retienen el mismo cupo a la vez: exactamente uno gana y nunca hay doble asignación. */
class CarreraPorCupoIT extends BaseIT {

    private static final int PACIENTES = 50;

    @Autowired
    ObjectMapper json;

    @Test
    void cincuentaHilosRetienenElMismoCupoYGanaExactamenteUno() throws Exception {
        List<UUID> cupos = crearCupos(ROSERO, SEDE_CENTRO, 7);
        UUID objetivo = cupos.get(0);

        ExecutorService pool = Executors.newFixedThreadPool(PACIENTES);
        CountDownLatch listos = new CountDownLatch(PACIENTES);
        CountDownLatch salida = new CountDownLatch(1);
        List<UUID> pacientes = new ArrayList<>();
        List<Future<MockHttpServletResponse>> futuros = new ArrayList<>();
        for (int i = 0; i < PACIENTES; i++) {
            UUID sub = UUID.randomUUID();
            pacientes.add(sub);
            String documento = "CARRERA-" + sub;
            Callable<MockHttpServletResponse> tarea = () -> {
                listos.countDown();
                salida.await();
                return mvc.perform(post("/api/agenda/retenciones").with(paciente(sub, "Paciente " + sub, documento))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupoId\":\"" + objetivo + "\"}"))
                        .andReturn().getResponse();
            };
            futuros.add(pool.submit(tarea));
        }
        listos.await();
        salida.countDown();

        int ganadores = 0;
        int perdedores = 0;
        int indiceGanador = -1;
        for (int i = 0; i < PACIENTES; i++) {
            MockHttpServletResponse r = futuros.get(i).get();
            if (r.getStatus() == 201) {
                ganadores++;
                indiceGanador = i;
            } else {
                assertThat(r.getStatus()).isEqualTo(409);
                JsonNode cuerpo = json.readTree(r.getContentAsString());
                assertThat(cuerpo.get("codigo").asText()).isEqualTo("CUPO_NO_DISPONIBLE");
                assertThat(cuerpo.get("alternativas")).hasSize(5);
                perdedores++;
            }
        }
        pool.shutdown();
        assertThat(ganadores).as("ganadores").isEqualTo(1);
        assertThat(perdedores).isEqualTo(PACIENTES - 1);

        // El ganador confirma con su clave de idempotencia
        UUID ganador = pacientes.get(indiceGanador);
        var confirmacion = mvc.perform(post("/api/agenda/citas").with(paciente(ganador, "Paciente " + ganador, "CARRERA-" + ganador))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupoId\":\"" + objetivo + "\"}"))
                .andReturn().getResponse();
        assertThat(confirmacion.getStatus()).isEqualTo(201);

        // Un perdedor no puede confirmar sin retención
        UUID perdedor = pacientes.get(indiceGanador == 0 ? 1 : 0);
        var rechazo = mvc.perform(post("/api/agenda/citas").with(paciente(perdedor, "Paciente " + perdedor, "CARRERA-" + perdedor))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupoId\":\"" + objetivo + "\"}"))
                .andReturn().getResponse();
        assertThat(rechazo.getStatus()).isEqualTo(409);

        // 0 dobles asignaciones
        long citasVivas = jdbc.sql("SELECT count(*) FROM agenda.cita WHERE cupo_id = :c AND estado <> 'CANCELADA'")
                .param("c", objetivo).query(Long.class).single();
        assertThat(citasVivas).isEqualTo(1);
        assertThat(jdbc.sql("SELECT estado FROM agenda.cupo WHERE id = :c").param("c", objetivo).query(String.class).single())
                .isEqualTo("RESERVADO");
        assertThat(jdbc.sql("SELECT count(*) FROM (SELECT cupo_id FROM agenda.cita WHERE estado <> 'CANCELADA' "
                + "GROUP BY cupo_id HAVING count(*) > 1) d").query(Long.class).single()).isZero();
    }
}
