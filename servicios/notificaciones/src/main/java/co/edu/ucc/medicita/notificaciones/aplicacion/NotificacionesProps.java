package co.edu.ucc.medicita.notificaciones.aplicacion;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración general; los datos propios de cada canal viven en su adaptador. */
@ConfigurationProperties("medicita.notificaciones")
public record NotificacionesProps(ZoneId zona, List<String> respaldo, int maxIntentos) {

    public NotificacionesProps {
        if (zona == null) zona = ZoneId.of("America/Bogota");
        if (respaldo == null || respaldo.isEmpty()) respaldo = List.of("WHATSAPP", "SMS", "CORREO");
        if (maxIntentos <= 0) maxIntentos = 5;
    }
}
