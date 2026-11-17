package co.edu.ucc.medicita.simuladores.hce;

import java.util.LinkedHashMap;
import java.util.Map;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/** Los tres almacenes de la HCE simulada y los pacientes precargados (datos semilla compartidos). */
@Component
public class AlmacenesHce {

    public static final String SISTEMA_PACIENTE = "urn:medicita:paciente";
    public static final String SISTEMA_DOCUMENTO = "urn:medicita:documento";

    private final AlmacenFhir<Patient> pacientes = new AlmacenFhir<>("Patient", Patient::getIdentifier);
    private final AlmacenFhir<Appointment> citas = new AlmacenFhir<>("Appointment", Appointment::getIdentifier);
    private final AlmacenFhir<Encounter> encuentros = new AlmacenFhir<>("Encounter", Encounter::getIdentifier);

    public AlmacenesHce() {
        precargar("a0000000-0000-4000-8000-000000000001", "1085000001", "Ana", "Gómez",
                Enumerations.AdministrativeGender.FEMALE, "1950-03-14");
        precargar("a0000000-0000-4000-8000-000000000002", "1085000002", "Luis", "Pérez",
                Enumerations.AdministrativeGender.MALE, "1948-07-02");
    }

    public AlmacenFhir<Patient> pacientes() {
        return pacientes;
    }

    public AlmacenFhir<Appointment> citas() {
        return citas;
    }

    public AlmacenFhir<Encounter> encuentros() {
        return encuentros;
    }

    public Map<String, Integer> conteos() {
        Map<String, Integer> salida = new LinkedHashMap<>();
        salida.put("Patient", pacientes.total());
        salida.put("Appointment", citas.total());
        salida.put("Encounter", encuentros.total());
        return salida;
    }

    private void precargar(String id, String cedula, String nombre, String apellido,
                           Enumerations.AdministrativeGender genero, String nacimiento) {
        Patient p = new Patient();
        p.addIdentifier().setSystem(SISTEMA_PACIENTE).setValue(id);
        p.addIdentifier().setSystem(SISTEMA_DOCUMENTO).setValue(cedula)
                .setType(new CodeableConcept().setText("CC"));
        p.addName().setFamily(apellido).addGiven(nombre);
        p.setGender(genero);
        p.setBirthDateElement(new DateType(nacimiento));
        p.setActive(true);
        pacientes.precargar(id, p);
    }
}
