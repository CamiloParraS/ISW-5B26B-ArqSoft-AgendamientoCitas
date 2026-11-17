package co.edu.ucc.medicita.compartido;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Zona horaria del dominio (America/Bogota) y utilidades de formato. */
public final class Tiempo {

    public static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private Tiempo() {
    }

    public static OffsetDateTime bogota(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONA).toOffsetDateTime();
    }

    public static OffsetDateTime ahora() {
        return OffsetDateTime.now(ZONA);
    }

    public static LocalDate hoy() {
        return LocalDate.now(ZONA);
    }

    public static OffsetDateTime inicioDelDia(LocalDate fecha) {
        return fecha.atStartOfDay(ZONA).toOffsetDateTime();
    }

    public static String iso(OffsetDateTime t) {
        return t == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(bogota(t));
    }
}
