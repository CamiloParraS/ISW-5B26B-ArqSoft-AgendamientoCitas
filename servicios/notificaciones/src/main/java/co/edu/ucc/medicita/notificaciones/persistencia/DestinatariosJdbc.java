package co.edu.ucc.medicita.notificaciones.persistencia;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.Destinatario;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DestinatariosJdbc {

    private final JdbcClient jdbc;

    public DestinatariosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Destinatario> buscar(UUID pacienteId) {
        return jdbc.sql("""
                SELECT paciente_id, nombre, telefono, email, canal_preferido
                FROM notificaciones.destinatario WHERE paciente_id = :id
                """)
                .param("id", pacienteId)
                .query((rs, n) -> new Destinatario(rs.getObject("paciente_id", UUID.class), rs.getString("nombre"),
                        rs.getString("telefono"), rs.getString("email"),
                        Canal.of(rs.getString("canal_preferido"))))
                .optional();
    }
}
