package co.edu.ucc.medicita.integracionclinica.eventos;

import java.util.UUID;

/** Sobre ya validado de un evento v1. */
public record Evento(UUID eventoId, String tipo, String correlacionId, DatosCita datos) {
}
