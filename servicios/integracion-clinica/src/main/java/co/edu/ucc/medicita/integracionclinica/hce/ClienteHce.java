package co.edu.ucc.medicita.integracionclinica.hce;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Optional;
import java.util.function.Supplier;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Cliente FHIR de la HCE. Solo habla FHIR (ningún DTO de eventos).
 * Cada llamada pasa por el circuit breaker "hce".
 */
@Component
public class ClienteHce {

    private static final TokenClientParam IDENTIFIER = new TokenClientParam("identifier");

    private final IGenericClient cliente;
    private final CircuitBreaker breaker;

    public ClienteHce(IGenericClient cliente, CircuitBreaker circuitBreakerHce) {
        this.cliente = cliente;
        this.breaker = circuitBreakerHce;
    }

    public Optional<Patient> buscarPaciente(String sistema, String valor) {
        return primero(protegido(() -> buscar("Patient", sistema, valor)), Patient.class);
    }

    public Optional<Appointment> buscarCita(String sistema, String valor) {
        return primero(protegido(() -> buscar("Appointment", sistema, valor)), Appointment.class);
    }

    /** Create condicional (If-None-Exist por identifier). Devuelve la referencia relativa "Tipo/id". */
    public String crearSiAusente(Resource recurso, String sistema, String valor) {
        String tipo = recurso.fhirType();
        MethodOutcome resultado = protegido(() -> cliente.create().resource(recurso)
                .conditional()
                .where(criterio(sistema, valor))
                .execute());
        return referencia(tipo, resultado, sistema, valor);
    }

    /** Update condicional por identifier. Devuelve la referencia relativa "Appointment/id". */
    public String actualizarCita(Appointment cita, String sistema, String valor) {
        MethodOutcome resultado = protegido(() -> cliente.update().resource(cita)
                .conditional()
                .where(criterio(sistema, valor))
                .execute());
        return referencia("Appointment", resultado, sistema, valor);
    }

    private String referencia(String tipo, MethodOutcome resultado, String sistema, String valor) {
        if (resultado.getId() != null && resultado.getId().hasIdPart()) {
            return tipo + "/" + resultado.getId().getIdPart();
        }
        Bundle bundle = protegido(() -> buscar(tipo, sistema, valor));
        return bundle.getEntry().stream().findFirst()
                .map(e -> tipo + "/" + e.getResource().getIdElement().getIdPart())
                .orElseThrow(() -> new IllegalStateException("La HCE no devolvió el recurso " + tipo));
    }

    private Bundle buscar(String tipo, String sistema, String valor) {
        return cliente.search().forResource(tipo).where(criterio(sistema, valor))
                .returnBundle(Bundle.class).execute();
    }

    private static ICriterion<TokenClientParam> criterio(String sistema, String valor) {
        return IDENTIFIER.exactly().systemAndIdentifier(sistema, valor);
    }

    private <T extends Resource> Optional<T> primero(Bundle bundle, Class<T> tipo) {
        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(tipo::isInstance).map(tipo::cast).findFirst();
    }

    private <T> T protegido(Supplier<T> llamada) {
        return breaker.executeSupplier(llamada);
    }
}
