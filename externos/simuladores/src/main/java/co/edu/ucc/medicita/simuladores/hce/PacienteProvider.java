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
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

@Component
public class PacienteProvider implements IResourceProvider {

    private final AlmacenesHce almacenes;

    public PacienteProvider(AlmacenesHce almacenes) {
        this.almacenes = almacenes;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Patient.class;
    }

    @Read
    public Patient leer(@IdParam IdType id) {
        return almacenes.pacientes().leer(id.getIdPart())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Patient> buscar(@OptionalParam(name = Patient.SP_IDENTIFIER) TokenParam identificador) {
        return identificador == null
                ? almacenes.pacientes().buscar(null, null)
                : almacenes.pacientes().buscar(identificador.getSystem(), identificador.getValue());
    }

    @Create
    public MethodOutcome crear(@ResourceParam Patient paciente, @ConditionalUrlParam String condicion,
                               RequestDetails detalles) {
        var resultado = almacenes.pacientes().crear(paciente, condicion);
        Anotaciones.anotar(detalles, (resultado.creado() ? "Creado " : "Ya existía ")
                + "Patient/" + resultado.recurso().getIdElement().getIdPart());
        MethodOutcome salida = new MethodOutcome(resultado.recurso().getIdElement(), resultado.creado());
        return salida;
    }
}
