package co.edu.ucc.medicita.prestadores;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Evento de aplicación: Prestadores publicó un horario de atención. Agenda lo atiende y genera los cupos.
 * diasSemana usa días ISO (1 = lunes ... 7 = domingo).
 */
public record HorarioPublicado(UUID horarioId, UUID medicoId, UUID sedeId, LocalDate desde, LocalDate hasta,
                               List<Integer> diasSemana, LocalTime horaInicio, LocalTime horaFin,
                               int duracionMinutos) {
}
