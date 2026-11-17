package co.edu.ucc.medicita.integracionclinica.anticorrupcion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.ucc.medicita.integracionclinica.eventos.CitaAgendada;
import co.edu.ucc.medicita.integracionclinica.eventos.Evento;
import co.edu.ucc.medicita.integracionclinica.eventos.EventoMalformadoException;
import co.edu.ucc.medicita.integracionclinica.eventos.LectorEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LectorEventosTest {

    private final LectorEventos lector = new LectorEventos(new ObjectMapper().findAndRegisterModules());

    @Test
    void leeUnaCitaAgendadaV1() {
        String json = """
                {"eventoId":"22222222-2222-4222-8222-222222222222","tipo":"CitaAgendada","version":1,
                 "ocurridoEn":"2026-11-17T12:00:00-05:00","correlacionId":"abc",
                 "datos":{"citaId":"11111111-1111-4111-8111-111111111111",
                  "pacienteId":"a0000000-0000-4000-8000-000000000001",
                  "medicoId":"b0000000-0000-4000-8000-000000000001","medicoNombre":"Carolina Rosero",
                  "especialidad":"Medicina general","sedeId":"c0000000-0000-4000-8000-000000000001",
                  "sedeNombre":"Sede Centro","inicio":"2026-11-18T07:00:00-05:00","fin":"2026-11-18T07:20:00-05:00"}}
                """;

        Evento evento = lector.leer(json.getBytes(StandardCharsets.UTF_8));

        assertThat(evento.tipo()).isEqualTo("CitaAgendada");
        assertThat(evento.correlacionId()).isEqualTo("abc");
        assertThat(evento.datos()).isInstanceOf(CitaAgendada.class);
        assertThat(((CitaAgendada) evento.datos()).medicoNombre()).isEqualTo("Carolina Rosero");
    }

    @Test
    void jsonMalformadoEsEventoMalformado() {
        assertThatThrownBy(() -> lector.leer("{no es json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EventoMalformadoException.class);
    }

    @Test
    void tipoDesconocidoOCamposFaltantesSonMalformados() {
        String desconocido = "{\"eventoId\":\"22222222-2222-4222-8222-222222222222\",\"tipo\":\"Otra\",\"version\":1,\"datos\":{}}";
        String incompleto = "{\"eventoId\":\"22222222-2222-4222-8222-222222222222\",\"tipo\":\"CitaAtendida\",\"version\":1,\"datos\":{\"citaId\":\"11111111-1111-4111-8111-111111111111\"}}";
        assertThatThrownBy(() -> lector.leer(desconocido.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EventoMalformadoException.class);
        assertThatThrownBy(() -> lector.leer(incompleto.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EventoMalformadoException.class);
    }
}
