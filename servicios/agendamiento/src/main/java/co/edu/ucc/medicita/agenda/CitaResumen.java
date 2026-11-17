package co.edu.ucc.medicita.agenda;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CitaResumen(UUID citaId, OffsetDateTime inicio, OffsetDateTime fin, String sede, String medico) {
}
