package co.edu.ucc.medicita.integracionclinica.anticorrupcion;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.ucc.medicita.integracionclinica.eventos.CitaAgendada;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaAtendida;
import co.edu.ucc.medicita.integracionclinica.eventos.CitaCancelada;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

class TraductorFhirTest {

    private final TraductorFhir traductor = new TraductorFhir();

    private final UUID citaId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID pacienteId = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private final UUID medicoId = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private final OffsetDateTime inicio = OffsetDateTime.parse("2026-11-18T07:00:00-05:00");
    private final OffsetDateTime fin = OffsetDateTime.parse("2026-11-18T07:20:00-05:00");

    @Test
    void citaAgendadaSeTraduceAAppointmentBooked() {
        var evento = new CitaAgendada(citaId, pacienteId, medicoId, "Carolina Rosero", "Medicina general",
                UUID.randomUUID(), "Sede Centro", inicio, fin);

        Appointment a = traductor.citaAgendada(evento, "Patient/p1");

        assertThat(a.getStatus()).isEqualTo(Appointment.AppointmentStatus.BOOKED);
        assertThat(a.getIdentifierFirstRep().getSystem()).isEqualTo("urn:medicita:cita");
        assertThat(a.getIdentifierFirstRep().getValue()).isEqualTo(citaId.toString());
        assertThat(a.getStartElement().getValueAsString()).isEqualTo("2026-11-18T07:00:00-05:00");
        assertThat(a.getEnd().toInstant()).isEqualTo(fin.toInstant());
        assertThat(a.getServiceTypeFirstRep().getText()).isEqualTo("Medicina general");
        assertThat(a.getParticipant()).hasSize(2);
        assertThat(a.getParticipant().get(0).getActor().getReference()).isEqualTo("Patient/p1");
        var medico = a.getParticipant().get(1).getActor();
        assertThat(medico.getDisplay()).isEqualTo("Carolina Rosero");
        assertThat(medico.getIdentifier().getSystem()).isEqualTo("urn:medicita:medico");
        assertThat(medico.getIdentifier().getValue()).isEqualTo(medicoId.toString());
    }

    @Test
    void pacienteMinimoLlevaSoloElIdentificadorDeMediCita() {
        Patient p = traductor.pacienteMinimo(pacienteId);

        assertThat(p.getIdentifier()).hasSize(1);
        assertThat(p.getIdentifierFirstRep().getSystem()).isEqualTo("urn:medicita:paciente");
        assertThat(p.getIdentifierFirstRep().getValue()).isEqualTo(pacienteId.toString());
        assertThat(p.hasName()).isFalse();
    }

    @Test
    void cancelarCambiaSoloElEstado() {
        var evento = new CitaAgendada(citaId, pacienteId, medicoId, "Carolina Rosero", "Medicina general",
                UUID.randomUUID(), "Sede Centro", inicio, fin);
        Appointment existente = traductor.citaAgendada(evento, "Patient/p1");

        Appointment cancelada = traductor.cancelar(existente);

        assertThat(cancelada.getStatus()).isEqualTo(Appointment.AppointmentStatus.CANCELLED);
        assertThat(cancelada.getServiceTypeFirstRep().getText()).isEqualTo("Medicina general");
        assertThat(cancelada.hasEnd()).isTrue();
    }

    @Test
    void citaCanceladaMinimaSirveSiLaAgendadaNoLlego() {
        var evento = new CitaCancelada(citaId, pacienteId, medicoId, "Carolina Rosero", "Sede Centro",
                inicio, true, "PACIENTE");

        Appointment a = traductor.citaCanceladaMinima(evento, "Patient/p1");

        assertThat(a.getStatus()).isEqualTo(Appointment.AppointmentStatus.CANCELLED);
        assertThat(a.getIdentifierFirstRep().getValue()).isEqualTo(citaId.toString());
        assertThat(a.getParticipant()).hasSize(2);
    }

    @Test
    void citaAtendidaSeTraduceAAppointmentFulfilledYEncounterFinished() {
        var evento = new CitaAtendida(citaId, pacienteId, medicoId, "Carolina Rosero", "Medicina general",
                "Sede Centro", inicio, fin, OffsetDateTime.parse("2026-11-18T07:25:00-05:00"));

        Appointment a = traductor.citaAtendida(evento, "Patient/p1");
        Encounter e = traductor.encuentro(evento, "Patient/p1", "Appointment/c1");

        assertThat(a.getStatus()).isEqualTo(Appointment.AppointmentStatus.FULFILLED);
        assertThat(e.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
        assertThat(e.getIdentifierFirstRep().getSystem()).isEqualTo("urn:medicita:cita");
        assertThat(e.getIdentifierFirstRep().getValue()).isEqualTo(citaId.toString());
        assertThat(e.getClass_().getCode()).isEqualTo("AMB");
        assertThat(e.getPeriod().getStart().toInstant()).isEqualTo(inicio.toInstant());
        assertThat(e.getPeriod().getEnd().toInstant()).isEqualTo(fin.toInstant());
        assertThat(e.getSubject().getReference()).isEqualTo("Patient/p1");
        assertThat(e.getAppointmentFirstRep().getReference()).isEqualTo("Appointment/c1");
        assertThat(e.getServiceType().getText()).isEqualTo("Medicina general");
    }
}
