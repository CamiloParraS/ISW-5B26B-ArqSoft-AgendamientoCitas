package co.edu.ucc.medicita.notificaciones.dominio;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/** Utilidades de plantillas y formato de fechas en español (zona del dominio). */
public final class Plantillas {

    private static final String[] DIAS = {"lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    private Plantillas() {
    }

    /** Reemplaza {clave} por su valor. */
    public static String rellenar(String texto, Map<String, String> variables) {
        String resultado = texto;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            resultado = resultado.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return resultado;
    }

    /** "martes 18 de noviembre" */
    public static String fecha(OffsetDateTime instante, ZoneId zona) {
        ZonedDateTime z = instante.atZoneSameInstant(zona);
        return DIAS[z.getDayOfWeek().getValue() - 1] + " " + z.getDayOfMonth() + " de " + MESES[z.getMonthValue() - 1];
    }

    /** "07:00" */
    public static String hora(OffsetDateTime instante, ZoneId zona) {
        ZonedDateTime z = instante.atZoneSameInstant(zona);
        return String.format("%02d:%02d", z.getHour(), z.getMinute());
    }
}
