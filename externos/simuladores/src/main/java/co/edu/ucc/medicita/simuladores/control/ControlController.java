package co.edu.ucc.medicita.simuladores.control;

import co.edu.ucc.medicita.simuladores.hce.AlmacenesHce;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints de control para demos y escenarios. */
@RestController
@RequestMapping("/control")
public class ControlController {

    private final EstadoSimuladores estado;
    private final AlmacenesHce hce;

    public ControlController(EstadoSimuladores estado, AlmacenesHce hce) {
        this.estado = estado;
        this.hce = hce;
    }

    @PostMapping("/hce")
    public ResponseEntity<?> hce(@RequestBody Map<String, Object> cuerpo) {
        Boolean caida = booleano(cuerpo, "caida");
        if (caida == null) {
            return invalido("Se espera {\"caida\": true|false}");
        }
        estado.hceCaida(caida);
        return ResponseEntity.ok(estado());
    }

    @PostMapping("/sms")
    public ResponseEntity<?> sms(@RequestBody Map<String, Object> cuerpo) {
        Boolean caida = booleano(cuerpo, "caida");
        if (caida == null) {
            return invalido("Se espera {\"caida\": true|false}");
        }
        estado.smsCaida(caida);
        return ResponseEntity.ok(estado());
    }

    @PostMapping("/whatsapp/plantillas/{nombre}")
    public ResponseEntity<?> plantilla(@PathVariable String nombre, @RequestBody Map<String, Object> cuerpo) {
        Boolean rechazada = booleano(cuerpo, "rechazada");
        if (rechazada == null) {
            return invalido("Se espera {\"rechazada\": true|false}");
        }
        if (!EstadoSimuladores.PLANTILLAS_APROBADAS.contains(nombre)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("codigo", "PLANTILLA_DESCONOCIDA", "mensaje", "La plantilla no existe: " + nombre));
        }
        estado.plantillaRechazada(nombre, rechazada);
        return ResponseEntity.ok(estado());
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("hceCaida", estado.hceCaida());
        salida.put("smsCaida", estado.smsCaida());
        salida.put("plantillasRechazadas", new TreeSet<>(estado.plantillasRechazadas()));
        salida.put("recursosHce", hce.conteos());
        return salida;
    }

    private static Boolean booleano(Map<String, Object> cuerpo, String clave) {
        return cuerpo.get(clave) instanceof Boolean b ? b : null;
    }

    private static ResponseEntity<?> invalido(String mensaje) {
        return ResponseEntity.badRequest().body(Map.of("codigo", "SOLICITUD_INVALIDA", "mensaje", mensaje));
    }
}
