package co.edu.ucc.medicita.simuladores.sms;

import co.edu.ucc.medicita.simuladores.control.EstadoSimuladores;
import co.edu.ucc.medicita.simuladores.registro.Registro;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Pasarela SMS simulada. */
@RestController
@RequestMapping("/sms/v1/mensajes")
public class SmsController {

    private final EstadoSimuladores estado;
    private final Registro registro;
    private final Map<String, Map<String, Object>> mensajes = new ConcurrentHashMap<>();

    public SmsController(EstadoSimuladores estado, Registro registro) {
        this.estado = estado;
        this.registro = registro;
    }

    @PostMapping
    public ResponseEntity<?> enviar(@RequestBody Map<String, Object> cuerpo,
                                    @RequestHeader(value = "X-Correlation-Id", required = false) String correlacion) {
        Object para = cuerpo.get("para");
        Object texto = cuerpo.get("texto");
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("para", para);
        entrada.put("texto", texto);
        if (estado.smsCaida()) {
            entrada.put("resultado", "CAIDA");
            registro.sms(correlacion, entrada);
            return noDisponible();
        }
        if (!(para instanceof String p) || p.isBlank() || !(texto instanceof String t) || t.isBlank()) {
            entrada.put("resultado", "INVALIDO");
            registro.sms(correlacion, entrada);
            return ResponseEntity.badRequest()
                    .body(Map.of("codigo", "SOLICITUD_INVALIDA", "mensaje", "Se requieren 'para' y 'texto'"));
        }
        String id = "sms-" + UUID.randomUUID();
        Map<String, Object> mensaje = new LinkedHashMap<>();
        mensaje.put("id", id);
        mensaje.put("estado", "ENVIADO");
        mensajes.put(id, mensaje);
        entrada.put("id", id);
        entrada.put("resultado", "ENVIADO");
        registro.sms(correlacion, entrada);
        return ResponseEntity.status(HttpStatus.CREATED).body(mensaje);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> consultar(@PathVariable String id) {
        if (estado.smsCaida()) {
            return noDisponible();
        }
        Map<String, Object> mensaje = mensajes.get(id);
        if (mensaje == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("codigo", "MENSAJE_NO_ENCONTRADO", "mensaje", "No existe el mensaje " + id));
        }
        return ResponseEntity.ok(mensaje);
    }

    private static ResponseEntity<?> noDisponible() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("codigo", "PASARELA_NO_DISPONIBLE", "mensaje", "La pasarela SMS está caída (simulada)"));
    }
}
