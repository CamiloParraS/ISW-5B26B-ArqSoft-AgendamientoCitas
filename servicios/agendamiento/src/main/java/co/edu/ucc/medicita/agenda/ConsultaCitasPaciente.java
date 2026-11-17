package co.edu.ucc.medicita.agenda;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Interfaz pública de Agenda hacia Acceso interinstitucional ("consulta por pacienteId"). */
public interface ConsultaCitasPaciente {

    Optional<UUID> pacienteIdPorDocumento(String documento);

    /** Citas confirmadas y futuras del paciente. */
    List<CitaResumen> citasVigentesDe(UUID pacienteId);
}
