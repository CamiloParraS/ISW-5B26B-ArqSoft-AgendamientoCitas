package co.edu.ucc.medicita.integracionclinica.web;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint interno (no enrutado por el gateway): estado del circuit breaker de la HCE. */
@RestController
public class EstadoHceController {

    private final CircuitBreaker breaker;

    public EstadoHceController(CircuitBreaker circuitBreakerHce) {
        this.breaker = circuitBreakerHce;
    }

    @GetMapping("/interno/hce/estado")
    public Map<String, String> estado() {
        return Map.of("estadoCircuito", breaker.getState().name());
    }
}
