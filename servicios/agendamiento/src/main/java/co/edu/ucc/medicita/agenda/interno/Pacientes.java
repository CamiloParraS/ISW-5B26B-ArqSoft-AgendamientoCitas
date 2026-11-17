package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.UsuarioActual;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Pacientes de la agenda (documento, nombre, teléfono): se actualizan desde los claims del token. */
@Component
class Pacientes {

    private final JdbcClient jdbc;

    Pacientes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert en cada petición de paciente; sólo escribe si algo cambió (evita bloqueos en filas calientes). */
    void asegurar(UsuarioActual u) {
        String documento = u.documento() == null || u.documento().isBlank() ? "SIN-" + u.id() : u.documento();
        jdbc.sql("""
                        INSERT INTO agenda.paciente(id, documento, nombre, telefono) VALUES (:id, :doc, :nombre, :tel)
                        ON CONFLICT (id) DO UPDATE SET documento = EXCLUDED.documento, nombre = EXCLUDED.nombre,
                                                       telefono = EXCLUDED.telefono
                        WHERE (agenda.paciente.documento, agenda.paciente.nombre, agenda.paciente.telefono)
                              IS DISTINCT FROM (EXCLUDED.documento, EXCLUDED.nombre, EXCLUDED.telefono)""")
                .param("id", u.id()).param("doc", documento).param("nombre", u.nombre()).param("tel", u.telefono())
                .update();
    }
}
