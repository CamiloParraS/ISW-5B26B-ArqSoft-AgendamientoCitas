package co.edu.ucc.medicita.integracionclinica.eventos;

import java.util.UUID;

public sealed interface DatosCita permits CitaAgendada, CitaCancelada, CitaAtendida {

    UUID citaId();

    UUID pacienteId();
}
