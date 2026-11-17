package co.edu.ucc.medicita.integracionclinica.anticorrupcion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import co.edu.ucc.medicita.integracionclinica.hce.FalloDeDisponibilidad;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

class FalloDeDisponibilidadTest {

    @Test
    void clasificaErroresDeDisponibilidad() {
        assertThat(FalloDeDisponibilidad.esDisponibilidad(new FhirClientConnectionException("sin red"))).isTrue();
        assertThat(FalloDeDisponibilidad.esDisponibilidad(new UnclassifiedServerFailureException(503, "caída"))).isTrue();
        assertThat(FalloDeDisponibilidad.esDisponibilidad(
                new RuntimeException(new java.net.SocketTimeoutException("timeout")))).isTrue();
        CircuitBreaker abierto = CircuitBreaker.ofDefaults("t");
        abierto.transitionToOpenState();
        assertThat(FalloDeDisponibilidad.esDisponibilidad(CallNotPermittedException.createCallNotPermittedException(abierto)))
                .isTrue();
    }

    @Test
    void erroresDeCliente4xxSonPermanentes() {
        assertThat(FalloDeDisponibilidad.esDisponibilidad(new InvalidRequestException("mal"))).isFalse();
        assertThat(FalloDeDisponibilidad.esDisponibilidad(new UnprocessableEntityException("no"))).isFalse();
        assertThat(FalloDeDisponibilidad.esDisponibilidad(new IllegalStateException("x"))).isFalse();
    }

    @Test
    void elBreakerSoloCuentaFallosDeDisponibilidadNoRechazosDelBreaker() {
        var predicado = new FalloDeDisponibilidad();
        CircuitBreaker abierto = CircuitBreaker.ofDefaults("t2");
        abierto.transitionToOpenState();
        assertThat(predicado.test(new UnclassifiedServerFailureException(500, "x"))).isTrue();
        assertThat(predicado.test(new InvalidRequestException("x"))).isFalse();
        assertThat(predicado.test(CallNotPermittedException.createCallNotPermittedException(abierto))).isFalse();
    }
}
