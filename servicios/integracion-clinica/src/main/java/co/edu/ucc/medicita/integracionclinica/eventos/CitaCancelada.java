package co.edu.ucc.medicita.integracionclinica.eventos;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Datos del evento CitaCancelada v1. */
public record CitaCancelada(UUID citaId, UUID pacienteId, UUID medicoId, String medicoNombre,
                            String sedeNombre, OffsetDateTime inicio, boolean conPenalidad,
                            String motivo) implements DatosCita {
}
