package co.edu.ucc.medicita.integracionclinica.eventos;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Datos del evento CitaAtendida v1. */
public record CitaAtendida(UUID citaId, UUID pacienteId, UUID medicoId, String medicoNombre,
                           String especialidad, String sedeNombre, OffsetDateTime inicio,
                           OffsetDateTime fin, OffsetDateTime atendidaEn) implements DatosCita {
}
