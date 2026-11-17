package co.edu.ucc.medicita.notificaciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlantillasTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Test
    void formateaFechaYHoraEnBogotaAunqueElInstanteVengaEnUtc() {
        OffsetDateTime utc = OffsetDateTime.parse("2026-11-18T12:00:00Z"); // 07:00 en Bogotá
        assertThat(Plantillas.fecha(utc, BOGOTA)).isEqualTo("miércoles 18 de noviembre");
        assertThat(Plantillas.hora(utc, BOGOTA)).isEqualTo("07:00");
    }

    @Test
    void martes17DeNoviembre() {
        OffsetDateTime t = OffsetDateTime.parse("2026-11-17T07:00:00-05:00");
        assertThat(Plantillas.fecha(t, BOGOTA)).isEqualTo("martes 17 de noviembre");
    }

    @Test
    void rellenaVariables() {
        String texto = Plantillas.rellenar("Cita con {medico} el {fecha}, {hora}, {sede}",
                Map.of("medico", "Carolina Rosero", "fecha", "martes 17 de noviembre", "hora", "07:00", "sede", "Sede Centro"));
        assertThat(texto).isEqualTo("Cita con Carolina Rosero el martes 17 de noviembre, 07:00, Sede Centro");
    }
}
