package co.edu.ucc.medicita.simuladores.hce;

import ca.uhn.fhir.rest.annotation.ConditionalUrlParam;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

@Component
public class CitaProvider implements IResourceProvider {

    private final AlmacenesHce almacenes;

    public CitaProvider(AlmacenesHce almacenes) {
        this.almacenes = almacenes;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Appointment.class;
    }

    @Read
    public Appointment leer(@IdParam IdType id) {
        return almacenes.citas().leer(id.getIdPart())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Appointment> buscar(@OptionalParam(name = Appointment.SP_IDENTIFIER) TokenParam identificador) {
        return identificador == null
                ? almacenes.citas().buscar(null, null)
                : almacenes.citas().buscar(identificador.getSystem(), identificador.getValue());
    }

    @Create
    public MethodOutcome crear(@ResourceParam Appointment cita, @ConditionalUrlParam String condicion,
                               RequestDetails detalles) {
        validar(cita);
        var resultado = almacenes.citas().crear(cita, condicion);
        anotar(detalles, resultado.creado() ? "Creada " : "Ya existía ", resultado.recurso());
        return new MethodOutcome(resultado.recurso().getIdElement(), resultado.creado());
    }

    @Update
    public MethodOutcome actualizar(@ResourceParam Appointment cita, @IdParam IdType id,
                                    @ConditionalUrlParam String condicion, RequestDetails detalles) {
        validar(cita);
        var resultado = almacenes.citas().actualizar(cita, id == null ? null : id.getIdPart(), condicion);
        anotar(detalles, resultado.creado() ? "Creada " : "Actualizada ", resultado.recurso());
        return new MethodOutcome(resultado.recurso().getIdElement(), resultado.creado());
    }

    private static void validar(Appointment cita) {
        if (!cita.hasStatus()) {
            throw new InvalidRequestException("Appointment.status es obligatorio");
        }
        if (cita.getParticipant().isEmpty()) {
            throw new InvalidRequestException("Appointment.participant es obligatorio");
        }
    }

    private static void anotar(RequestDetails detalles, String accion, Appointment cita) {
        String valor = cita.getIdentifier().isEmpty() ? "?" : cita.getIdentifierFirstRep().getValue();
        Anotaciones.anotar(detalles, accion + "Appointment/" + cita.getIdElement().getIdPart()
                + " estado=" + cita.getStatus().toCode() + " cita=" + valor);
    }
}
