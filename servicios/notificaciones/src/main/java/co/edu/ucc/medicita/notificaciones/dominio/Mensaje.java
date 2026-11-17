package co.edu.ucc.medicita.notificaciones.dominio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mensaje neutral al canal: cada adaptador lo convierte a su plantilla. Fecha y hora ya van en America/Bogota. */
public record Mensaje(TipoNotificacion tipo, UUID citaId, Destinatario destinatario,
                      String medico, String fecha, String hora, String sede, String correlacionId) {

    /** Variables de plantilla, en el orden de los parámetros del cuerpo de WhatsApp. */
    public Map<String, String> variables() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("medico", medico);
        v.put("fecha", fecha);
        v.put("hora", hora);
        v.put("sede", sede);
        return v;
    }

    /** Variables más el nombre del destinatario (para texto de correo). */
    public Map<String, String> variablesConNombre() {
        Map<String, String> v = variables();
        v.put("nombre", destinatario.nombre());
        return v;
    }
}
