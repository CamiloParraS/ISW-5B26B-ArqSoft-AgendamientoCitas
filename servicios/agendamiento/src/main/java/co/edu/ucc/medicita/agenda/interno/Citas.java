package co.edu.ucc.medicita.agenda.interno;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso SQL a agenda.cita y agenda.clave_idempotencia. */
@Repository
class Citas {

    private static final String COLUMNAS =
            "id, cupo_id, paciente_id, medico_id, sede_id, inicio, fin, estado, cancelada_en, con_penalidad";

    private final JdbcClient jdbc;

    Citas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CitaRow> porId(UUID id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cita WHERE id = :id").param("id", id)
                .query(Citas::mapear).optional();
    }

    /** Bloquea la fila durante la transacción (cancelación, reprogramación, atención). */
    Optional<CitaRow> bloquear(UUID id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cita WHERE id = :id FOR UPDATE").param("id", id)
                .query(Citas::mapear).optional();
    }

    List<CitaRow> delPaciente(UUID pacienteId) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cita WHERE paciente_id = :p ORDER BY inicio DESC")
                .param("p", pacienteId).query(Citas::mapear).list();
    }

    void insertar(UUID id, CupoRow cupo, UUID pacienteId) {
        jdbc.sql("""
                        INSERT INTO agenda.cita(id, cupo_id, paciente_id, medico_id, sede_id, inicio, fin, estado)
                        VALUES (:id, :c, :p, :m, :s, :i, :f, 'CONFIRMADA')""")
                .param("id", id).param("c", cupo.id()).param("p", pacienteId).param("m", cupo.medicoId())
                .param("s", cupo.sedeId()).param("i", cupo.inicio()).param("f", cupo.fin()).update();
    }

    Optional<ClaveRow> buscarClave(String clave) {
        return jdbc.sql("SELECT clave, paciente_id, operacion, cita_id FROM agenda.clave_idempotencia WHERE clave = :k")
                .param("k", clave)
                .query((rs, n) -> new ClaveRow(rs.getString("clave"), rs.getObject("paciente_id", UUID.class),
                        rs.getString("operacion"), rs.getObject("cita_id", UUID.class)))
                .optional();
    }

    void insertarClave(String clave, UUID pacienteId, String operacion, UUID citaId) {
        jdbc.sql("INSERT INTO agenda.clave_idempotencia(clave, paciente_id, operacion, cita_id) VALUES (:k, :p, :o, :c)")
                .param("k", clave).param("p", pacienteId).param("o", operacion).param("c", citaId).update();
    }

    /** Serializa las peticiones con la misma clave dentro de la transacción (reintentos concurrentes). */
    void bloquearClave(String clave) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", clave).query((rs, n) -> 1).list();
    }

    private static CitaRow mapear(ResultSet rs, int n) throws SQLException {
        return new CitaRow(rs.getObject("id", UUID.class), rs.getObject("cupo_id", UUID.class),
                rs.getObject("paciente_id", UUID.class), rs.getObject("medico_id", UUID.class),
                rs.getObject("sede_id", UUID.class), rs.getObject("inicio", OffsetDateTime.class),
                rs.getObject("fin", OffsetDateTime.class), rs.getString("estado"),
                rs.getObject("cancelada_en", OffsetDateTime.class), rs.getBoolean("con_penalidad"));
    }
}

record CitaRow(UUID id, UUID cupoId, UUID pacienteId, UUID medicoId, UUID sedeId, OffsetDateTime inicio,
               OffsetDateTime fin, String estado, OffsetDateTime canceladaEn, boolean conPenalidad) {
}

record ClaveRow(String clave, UUID pacienteId, String operacion, UUID citaId) {
}
