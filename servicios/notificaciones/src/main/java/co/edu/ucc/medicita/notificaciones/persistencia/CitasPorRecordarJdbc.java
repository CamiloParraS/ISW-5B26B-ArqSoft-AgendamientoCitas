package co.edu.ucc.medicita.notificaciones.persistencia;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CitasPorRecordarJdbc {

    private final JdbcClient jdbc;

    public CitasPorRecordarJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CitaPorRecordar(UUID citaId, UUID pacienteId, OffsetDateTime inicio, String medicoNombre,
                                  String sedeNombre) {
    }

    public void guardar(UUID citaId, UUID pacienteId, OffsetDateTime inicio, String medico, String sede) {
        jdbc.sql("""
                INSERT INTO notificaciones.cita_por_recordar (cita_id, paciente_id, inicio, medico_nombre, sede_nombre)
                VALUES (:cita, :paciente, :inicio, :medico, :sede)
                ON CONFLICT (cita_id) DO UPDATE SET paciente_id = EXCLUDED.paciente_id, inicio = EXCLUDED.inicio,
                  medico_nombre = EXCLUDED.medico_nombre, sede_nombre = EXCLUDED.sede_nombre, recordada = false
                """)
                .param("cita", citaId).param("paciente", pacienteId).param("inicio", inicio)
                .param("medico", medico).param("sede", sede).update();
    }

    public void eliminar(UUID citaId) {
        jdbc.sql("DELETE FROM notificaciones.cita_por_recordar WHERE cita_id = :cita").param("cita", citaId).update();
    }

    public List<CitaPorRecordar> pendientesEntre(OffsetDateTime desde, OffsetDateTime hasta) {
        return jdbc.sql("""
                SELECT cita_id, paciente_id, inicio, medico_nombre, sede_nombre
                FROM notificaciones.cita_por_recordar
                WHERE NOT recordada AND inicio >= :desde AND inicio < :hasta ORDER BY inicio
                """)
                .param("desde", desde).param("hasta", hasta)
                .query((rs, n) -> new CitaPorRecordar(rs.getObject("cita_id", UUID.class),
                        rs.getObject("paciente_id", UUID.class), rs.getObject("inicio", OffsetDateTime.class),
                        rs.getString("medico_nombre"), rs.getString("sede_nombre")))
                .list();
    }

    /** Reclama la cita para recordarla; false si otra ejecución ya lo hizo. */
    public boolean marcarRecordada(UUID citaId) {
        return jdbc.sql("UPDATE notificaciones.cita_por_recordar SET recordada = true WHERE cita_id = :c AND NOT recordada")
                .param("c", citaId).update() == 1;
    }

    public void desmarcarRecordada(UUID citaId) {
        jdbc.sql("UPDATE notificaciones.cita_por_recordar SET recordada = false WHERE cita_id = :c")
                .param("c", citaId).update();
    }
}
