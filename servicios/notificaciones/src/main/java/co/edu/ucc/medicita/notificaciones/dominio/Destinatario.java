package co.edu.ucc.medicita.notificaciones.dominio;

import java.util.UUID;

/** En este contexto el paciente es destinatario + canal preferido. */
public record Destinatario(UUID pacienteId, String nombre, String telefono, String email, Canal canalPreferido) {
}
