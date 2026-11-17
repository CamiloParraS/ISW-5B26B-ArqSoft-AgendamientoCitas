package co.edu.ucc.medicita.integracionclinica.hce;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.IOException;
import java.util.function.Predicate;

/**
 * Falla de disponibilidad de la HCE: breaker abierto, E/S, timeouts o 5xx.
 * Es el predicado de fallos del circuit breaker "hce" (sin contar CallNotPermitted)
 * y el criterio de reintento ilimitado.
 */
public class FalloDeDisponibilidad implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable error) {
        return !esBreakerAbierto(error) && esDisponibilidad(error);
    }

    public static boolean esBreakerAbierto(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CallNotPermittedException) {
                return true;
            }
        }
        return false;
    }

    public static boolean esDisponibilidad(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CallNotPermittedException) {
                return true;
            }
            if (t instanceof BaseServerResponseException respuesta && respuesta.getStatusCode() >= 500) {
                return true;
            }
            if (t instanceof FhirClientConnectionException || t instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
