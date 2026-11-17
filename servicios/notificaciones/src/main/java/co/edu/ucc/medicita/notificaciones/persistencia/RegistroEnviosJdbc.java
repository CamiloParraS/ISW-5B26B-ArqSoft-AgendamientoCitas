package co.edu.ucc.medicita.notificaciones.persistencia;

import co.edu.ucc.medicita.notificaciones.aplicacion.RegistroEnvios;
import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RegistroEnviosJdbc implements RegistroEnvios {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private final JdbcClient jdbc;

    public RegistroEnviosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(UUID eventoId, UUID citaId, UUID pacienteId, TipoNotificacion tipo, Canal canal,
                          ResultadoEnvio r, String correlacionId) {
        jdbc.sql("""
                INSERT INTO notificaciones.envio
                  (id, evento_id, cita_id, paciente_id, tipo, canal, estado, id_externo, detalle, correlacion_id)
                VALUES (:id, :evento, :cita, :paciente, :tipo, :canal, :estado, :idExterno, :detalle, :corr)
                """)
                .param("id", UUID.randomUUID())
                .param("evento", eventoId, java.sql.Types.OTHER)
                .param("cita", citaId)
                .param("paciente", pacienteId)
                .param("tipo", tipo.name())
                .param("canal", canal.codigo())
                .param("estado", r.estado().name())
                .param("idExterno", r.idExterno(), java.sql.Types.VARCHAR)
                .param("detalle", r.detalle(), java.sql.Types.VARCHAR)
                .param("corr", correlacionId, java.sql.Types.VARCHAR)
                .update();
    }

    public record EnvioVista(UUID id, UUID eventoId, UUID citaId, UUID pacienteId, String tipo, String canal,
                             String estado, String idExterno, String detalle, String correlacionId,
                             OffsetDateTime creadoEn) {
    }

    public List<EnvioVista> porCita(UUID citaId) {
        return jdbc.sql("""
                SELECT id, evento_id, cita_id, paciente_id, tipo, canal, estado, id_externo, detalle,
                       correlacion_id, creado_en
                FROM notificaciones.envio WHERE cita_id = :cita ORDER BY creado_en, id
                """)
                .param("cita", citaId)
                .query((rs, n) -> new EnvioVista(
                        rs.getObject("id", UUID.class), rs.getObject("evento_id", UUID.class),
                        rs.getObject("cita_id", UUID.class), rs.getObject("paciente_id", UUID.class),
                        rs.getString("tipo"), rs.getString("canal"), rs.getString("estado"),
                        rs.getString("id_externo"), rs.getString("detalle"), rs.getString("correlacion_id"),
                        rs.getObject("creado_en", OffsetDateTime.class).atZoneSameInstant(BOGOTA).toOffsetDateTime()))
                .list();
    }
}
