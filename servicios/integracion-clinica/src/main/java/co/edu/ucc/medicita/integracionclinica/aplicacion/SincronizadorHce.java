package co.edu.ucc.medicita.integracionclinica.aplicacion;

import co.edu.ucc.medicita.integracionclinica.anticorrupcion.SistemasFhir;
import co.edu.ucc.medicita.integracionclinica.anticorrupcion.TraductorFhir;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaAgendada;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaAtendida;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaCancelada;
import co.edu.ucc.medicita.integracionclinica.eventos.DatosCita;
import co.edu.ucc.medicita.integracionclinica.hce.ClienteHce;
import java.time.LocalDate;
import java.time.Period;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Caso de uso: sincronizar una cita con la HCE. Todas las operaciones son idempotentes
 * (create/update condicionales por identifier), por lo que no se necesita base de datos.
 */
@Service
public class SincronizadorHce {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorHce.class);

    private final ClienteHce hce;
    private final TraductorFhir traductor;

    public SincronizadorHce(ClienteHce hce, TraductorFhir traductor) {
        this.hce = hce;
        this.traductor = traductor;
    }

    public void procesar(DatosCita datos) {
        switch (datos) {
            case CitaAgendada d -> agendada(d);
            case CitaCancelada d -> cancelada(d);
            case CitaAtendida d -> atendida(d);
        }
    }

    private void agendada(CitaAgendada d) {
        String paciente = asegurarPaciente(d.pacienteId());
        String citaId = d.citaId().toString();
        String ref = hce.crearSiAusente(traductor.citaAgendada(d, paciente), SistemasFhir.CITA, citaId);
        log.info("Cita {} agendada en la HCE como {}", citaId, ref);
    }

    private void cancelada(CitaCancelada d) {
        String citaId = d.citaId().toString();
        Optional<Appointment> existente = hce.buscarCita(SistemasFhir.CITA, citaId);
        Appointment cita = existente.map(traductor::cancelar)
                .orElseGet(() -> traductor.citaCanceladaMinima(d, asegurarPaciente(d.pacienteId())));
        String ref = hce.actualizarCita(cita, SistemasFhir.CITA, citaId);
        log.info("Cita {} cancelada en la HCE ({})", citaId, ref);
    }

    private void atendida(CitaAtendida d) {
        String paciente = asegurarPaciente(d.pacienteId());
        String citaId = d.citaId().toString();
        Appointment cita = hce.buscarCita(SistemasFhir.CITA, citaId)
                .map(traductor::atender)
                .orElseGet(() -> traductor.citaAtendida(d, paciente));
        String citaRef = hce.actualizarCita(cita, SistemasFhir.CITA, citaId);
        String encuentro = hce.crearSiAusente(traductor.encuentro(d, paciente, citaRef), SistemasFhir.CITA, citaId);
        log.info("Cita {} atendida en la HCE: {} y {}", citaId, citaRef, encuentro);
    }

    /** Consulta datos demográficos; si el paciente no existe crea uno mínimo. Devuelve "Patient/id". */
    private String asegurarPaciente(UUID pacienteId) {
        String valor = pacienteId.toString();
        Optional<Patient> encontrado = hce.buscarPaciente(SistemasFhir.PACIENTE, valor);
        if (encontrado.isPresent()) {
            Patient p = encontrado.get();
            log.info("Paciente de la HCE: nombre={} edad={}", nombre(p), edad(p));
            return "Patient/" + p.getIdElement().getIdPart();
        }
        return hce.crearSiAusente(traductor.pacienteMinimo(pacienteId), SistemasFhir.PACIENTE, valor);
    }

    private static String nombre(Patient p) {
        return p.hasName() ? p.getNameFirstRep().getNameAsSingleString() : "(sin nombre)";
    }

    private static String edad(Patient p) {
        if (p.getBirthDate() == null) {
            return "desconocida";
        }
        LocalDate nacimiento = p.getBirthDate().toInstant().atZone(java.time.ZoneId.of("America/Bogota")).toLocalDate();
        return String.valueOf(Period.between(nacimiento, LocalDate.now()).getYears());
    }
}
