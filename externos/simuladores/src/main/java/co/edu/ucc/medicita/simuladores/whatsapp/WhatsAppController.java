package co.edu.ucc.medicita.simuladores.whatsapp;

import co.edu.ucc.medicita.simuladores.control.EstadoSimuladores;
import co.edu.ucc.medicita.simuladores.registro.Registro;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulador de la WhatsApp Business Cloud API (forma de Meta).
 * Fuera de la ventana de 24 h solo se aceptan plantillas aprobadas.
 */
@RestController
@RequestMapping("/whatsapp/v21.0")
public class WhatsAppController {

    private final EstadoSimuladores estado;
    private final Registro registro;

    public WhatsAppController(EstadoSimuladores estado, Registro registro) {
        this.estado = estado;
        this.registro = registro;
    }

    @PostMapping("/{phoneId}/messages")
    public ResponseEntity<?> enviar(@PathVariable String phoneId,
                                    @RequestHeader(value = "Authorization", required = false) String autorizacion,
                                    @RequestHeader(value = "X-Correlation-Id", required = false) String correlacion,
                                    @RequestBody Map<String, Object> cuerpo) {
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("phoneId", phoneId);
        entrada.put("para", cuerpo.get("to"));
        entrada.put("tipo", cuerpo.get("type"));

        if (autorizacion == null || !autorizacion.regionMatches(true, 0, "Bearer ", 0, 7) || autorizacion.length() <= 7) {
            return rechazar(entrada, correlacion, HttpStatus.UNAUTHORIZED,
                    "Invalid OAuth access token - Cannot parse access token", 190);
        }
        if (!(cuerpo.get("to") instanceof String destino) || destino.isBlank()) {
            return rechazar(entrada, correlacion, HttpStatus.BAD_REQUEST, "Parameter 'to' is required", 100);
        }
        if (!"template".equals(cuerpo.get("type")) || !(cuerpo.get("template") instanceof Map<?, ?> plantilla)) {
            // Regla de Meta: fuera de la ventana de 24 h solo plantillas
            return rechazar(entrada, correlacion, HttpStatus.BAD_REQUEST,
                    "Re-engagement message: more than 24 hours have passed since the customer last replied; only template messages are allowed",
                    131047);
        }
        String nombre = String.valueOf(plantilla.get("name"));
        entrada.put("plantilla", nombre);
        entrada.put("parametros", parametros(plantilla));
        if (!EstadoSimuladores.PLANTILLAS_APROBADAS.contains(nombre)) {
            return rechazar(entrada, correlacion, HttpStatus.NOT_FOUND,
                    "Template name does not exist in the translation", 132001);
        }
        if (estado.plantillaRechazada(nombre)) {
            return rechazar(entrada, correlacion, HttpStatus.BAD_REQUEST, "Template paused", 132015);
        }

        String wamid = "wamid." + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        entrada.put("resultado", "ENVIADO");
        entrada.put("waMessageId", wamid);
        registro.whatsapp(correlacion, entrada);

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("messaging_product", "whatsapp");
        respuesta.put("contacts", List.of(Map.of("input", destino, "wa_id", destino.replaceAll("\\D", ""))));
        respuesta.put("messages", List.of(Map.of("id", wamid)));
        return ResponseEntity.ok(respuesta);
    }

    private ResponseEntity<?> rechazar(Map<String, Object> entrada, String correlacion, HttpStatus estadoHttp,
                                       String mensaje, int codigo) {
        entrada.put("resultado", "RECHAZADO");
        entrada.put("codigoError", codigo);
        entrada.put("mensajeError", mensaje);
        registro.whatsapp(correlacion, entrada);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", mensaje);
        error.put("type", "OAuthException");
        error.put("code", codigo);
        return ResponseEntity.status(estadoHttp).body(Map.of("error", error));
    }

    /** Textos de los parámetros del cuerpo de la plantilla, para el registro. */
    private static List<String> parametros(Map<?, ?> plantilla) {
        List<String> textos = new ArrayList<>();
        if (plantilla.get("components") instanceof List<?> componentes) {
            for (Object c : componentes) {
                if (c instanceof Map<?, ?> componente && componente.get("parameters") instanceof List<?> ps) {
                    for (Object p : ps) {
                        if (p instanceof Map<?, ?> parametro && parametro.get("text") != null) {
                            textos.add(String.valueOf(parametro.get("text")));
                        }
                    }
                }
            }
        }
        return textos;
    }
}
