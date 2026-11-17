package co.edu.ucc.medicita.integracionclinica.anticorrupcion;

import co.edu.ucc.medicita.integracionclinica.eventos.CitaAgendada;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaAtendida;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaCancelada;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import org.springframework.stereotype.Component;

/**
 * Capa anticorrupción: traduce los eventos v1 de MediCita a recursos FHIR R4.
 * Es la única clase que conoce ambos modelos; funciones puras, sin E/S.
 */
@Component
public class TraductorFhir {

    private static final TimeZone BOGOTA = TimeZone.getTimeZone("America/Bogota");

    /** Paciente mínimo cuando la HCE aún no lo conoce. */
    public Patient pacienteMinimo(UUID pacienteId) {
        Patient p = new Patient();
        p.addIdentifier(identificador(SistemasFhir.PACIENTE, pacienteId.toString()));
        p.setActive(true);
        return p;
    }

    /** CitaAgendada -> Appointment booked. */
    public Appointment citaAgendada(CitaAgendada d, String pacienteRef) {
        Appointment a = new Appointment();
        a.addIdentifier(identificador(SistemasFhir.CITA, d.citaId().toString()));
        a.setStatus(Appointment.AppointmentStatus.BOOKED);
        a.setStartElement(fecha(d.inicio()));
        a.setEndElement(fecha(d.fin()));
        a.addServiceType(new CodeableConcept().setText(d.especialidad()));
        a.setDescription("Cita de " + d.especialidad() + " en " + d.sedeNombre());
        a.addParticipant(participantePaciente(pacienteRef));
        a.addParticipant(participanteMedico(d.medicoId(), d.medicoNombre()));
        return a;
    }

    /** CitaCancelada -> cambio de estado a cancelled sobre el Appointment existente. */
    public Appointment cancelar(Appointment existente) {
        existente.setStatus(Appointment.AppointmentStatus.CANCELLED);
        return existente;
    }

    /** Appointment mínimo cancelado, si la cita agendada aún no llegó a la HCE. */
    public Appointment citaCanceladaMinima(CitaCancelada d, String pacienteRef) {
        Appointment a = new Appointment();
        a.addIdentifier(identificador(SistemasFhir.CITA, d.citaId().toString()));
        a.setStatus(Appointment.AppointmentStatus.CANCELLED);
        a.setStartElement(fecha(d.inicio()));
        a.addParticipant(participantePaciente(pacienteRef));
        a.addParticipant(participanteMedico(d.medicoId(), d.medicoNombre()));
        return a;
    }

    /** CitaAtendida -> Appointment fulfilled (sobre el existente). */
    public Appointment atender(Appointment existente) {
        existente.setStatus(Appointment.AppointmentStatus.FULFILLED);
        return existente;
    }

    /** Appointment fulfilled completo, si la cita agendada aún no llegó a la HCE. */
    public Appointment citaAtendida(CitaAtendida d, String pacienteRef) {
        Appointment a = new Appointment();
        a.addIdentifier(identificador(SistemasFhir.CITA, d.citaId().toString()));
        a.setStatus(Appointment.AppointmentStatus.FULFILLED);
        a.setStartElement(fecha(d.inicio()));
        a.setEndElement(fecha(d.fin()));
        a.addServiceType(new CodeableConcept().setText(d.especialidad()));
        a.setDescription("Cita de " + d.especialidad() + " en " + d.sedeNombre());
        a.addParticipant(participantePaciente(pacienteRef));
        a.addParticipant(participanteMedico(d.medicoId(), d.medicoNombre()));
        return a;
    }

    /** CitaAtendida -> Encounter finished con la misma identidad de la cita. */
    public Encounter encuentro(CitaAtendida d, String pacienteRef, String citaRef) {
        Encounter e = new Encounter();
        e.addIdentifier(identificador(SistemasFhir.CITA, d.citaId().toString()));
        e.setStatus(Encounter.EncounterStatus.FINISHED);
        e.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", "ambulatory"));
        e.setPeriod(new Period().setStartElement(fechaHora(d.inicio())).setEndElement(fechaHora(d.fin())));
        e.setSubject(new Reference(pacienteRef));
        e.addAppointment(new Reference(citaRef));
        e.setServiceType(new CodeableConcept().setText(d.especialidad()));
        e.addParticipant().setIndividual(referenciaMedico(d.medicoId(), d.medicoNombre()));
        return e;
    }

    private Appointment.AppointmentParticipantComponent participantePaciente(String pacienteRef) {
        return new Appointment.AppointmentParticipantComponent()
                .setActor(new Reference(pacienteRef))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    }

    private Appointment.AppointmentParticipantComponent participanteMedico(UUID medicoId, String nombre) {
        return new Appointment.AppointmentParticipantComponent()
                .setActor(referenciaMedico(medicoId, nombre))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    }

    private Reference referenciaMedico(UUID medicoId, String nombre) {
        Reference r = new Reference();
        r.setIdentifier(identificador(SistemasFhir.MEDICO, medicoId.toString()));
        r.setDisplay(nombre);
        return r;
    }

    private Identifier identificador(String sistema, String valor) {
        return new Identifier().setSystem(sistema).setValue(valor);
    }

    private InstantType fecha(OffsetDateTime odt) {
        return new InstantType(Date.from(odt.toInstant()), TemporalPrecisionEnum.SECOND, BOGOTA);
    }

    private DateTimeType fechaHora(OffsetDateTime odt) {
        return new DateTimeType(Date.from(odt.toInstant()), TemporalPrecisionEnum.SECOND, BOGOTA);
    }
}
