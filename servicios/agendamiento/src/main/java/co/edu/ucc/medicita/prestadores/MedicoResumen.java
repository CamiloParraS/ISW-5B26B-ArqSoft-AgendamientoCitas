package co.edu.ucc.medicita.prestadores;

import java.util.UUID;

public record MedicoResumen(UUID id, String nombre, UUID especialidadId, String especialidad, UUID sedeId,
                            String sede) {
}
