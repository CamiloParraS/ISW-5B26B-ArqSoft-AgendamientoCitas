package co.edu.ucc.medicita.simuladores.hce;

import ca.uhn.fhir.rest.annotation.ConditionalUrlParam;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

@Component
public class EncuentroProvider implements IResourceProvider {

    private final AlmacenesHce almacenes;

    public EncuentroProvider(AlmacenesHce almacenes) {
        this.almacenes = almacenes;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Encounter.class;
    }

    @Read
    public Encounter leer(@IdParam IdType id) {
        return almacenes.encuentros().leer(id.getIdPart())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Encounter> buscar(@OptionalParam(name = Encounter.SP_IDENTIFIER) TokenParam identificador) {
        return identificador == null
                ? almacenes.encuentros().buscar(null, null)
                : almacenes.encuentros().buscar(identificador.getSystem(), identificador.getValue());
    }

    @Create
    public MethodOutcome crear(@ResourceParam Encounter encuentro, @ConditionalUrlParam String condicion,
                               RequestDetails detalles) {
        if (!encuentro.hasStatus() || !encuentro.hasClass_()) {
            throw new InvalidRequestException("Encounter.status y Encounter.class son obligatorios");
        }
        var resultado = almacenes.encuentros().crear(encuentro, condicion);
        String valor = encuentro.getIdentifier().isEmpty() ? "?" : encuentro.getIdentifierFirstRep().getValue();
        Anotaciones.anotar(detalles, (resultado.creado() ? "Creado " : "Ya existía ") + "Encounter/"
                + resultado.recurso().getIdElement().getIdPart() + " estado=" + encuentro.getStatus().toCode()
                + " cita=" + valor);
        return new MethodOutcome(resultado.recurso().getIdElement(), resultado.creado());
    }
}
