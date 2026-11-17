package co.edu.ucc.medicita.prestadores;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Interfaz pública de Prestadores hacia Agenda (llamada por interfaz). */
public interface CatalogoPrestadores {

    Optional<MedicoResumen> medico(UUID medicoId);

    List<MedicoResumen> medicosPorEspecialidad(UUID especialidadId);

    Optional<SedeResumen> sede(UUID sedeId);
}
