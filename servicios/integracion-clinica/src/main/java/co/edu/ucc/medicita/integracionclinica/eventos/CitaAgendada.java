package co.edu.ucc.medicita.integracionclinica.eventos;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Datos del evento CitaAgendada v1 (contrato del broker, nunca FHIR). */
public record CitaAgendada(UUID citaId, UUID pacienteId, UUID medicoId, String medicoNombre,
                           String especialidad, UUID sedeId, String sedeNombre,
                           OffsetDateTime inicio, OffsetDateTime fin) implements DatosCita {
}
