package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.compartido.UsuarioActual;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agenda del médico: su día, bloqueo de cupos libres y marca de atención. */
@RestController
@RequestMapping("/api/agenda/medico")
@PreAuthorize("hasRole('medico')")
public class MedicoControlador {

    private final JdbcClient jdbc;
    private final Reservas reservas;
    private final CacheCupos cache;

    MedicoControlador(JdbcClient jdbc, Reservas reservas, CacheCupos cache) {
        this.jdbc = jdbc;
        this.reservas = reservas;
        this.cache = cache;
    }

    @GetMapping("/agenda")
    @Transactional(readOnly = true)
    public List<CupoMedicoDto> agenda(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String fecha) {
        UsuarioActual medico = UsuarioActual.de(jwt);
        LocalDate dia = CuposControlador.parsear(fecha);
        return jdbc.sql("""
                        SELECT c.id, c.inicio, c.fin, c.estado, c.sede_id, ci.id AS cita_id, ci.estado AS cita_estado,
                               p.nombre AS paciente_nombre
                          FROM agenda.cupo c
                          LEFT JOIN agenda.cita ci ON ci.cupo_id = c.id AND ci.estado <> 'CANCELADA'
                          LEFT JOIN agenda.paciente p ON p.id = ci.paciente_id
                         WHERE c.medico_id = :m AND c.inicio >= :d AND c.inicio < :h
                         ORDER BY c.inicio""")
                .param("m", medico.id()).param("d", Tiempo.inicioDelDia(dia)).param("h", Tiempo.inicioDelDia(dia.plusDays(1)))
                .query((rs, n) -> new CupoMedicoDto(rs.getObject("id", UUID.class),
                        rs.getObject("inicio", java.time.OffsetDateTime.class),
                        rs.getObject("fin", java.time.OffsetDateTime.class), rs.getString("estado"),
                        rs.getObject("sede_id", UUID.class), rs.getObject("cita_id", UUID.class),
                        rs.getString("cita_estado"), rs.getString("paciente_nombre")))
                .list();
    }

    @PostMapping("/cupos/{id}/bloqueo")
    @Transactional
    public Map<String, Object> bloquear(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cambiar(UsuarioActual.de(jwt), id, "LIBRE", "BLOQUEADO",
                "Sólo se puede bloquear un cupo propio que esté libre");
    }

    @DeleteMapping("/cupos/{id}/bloqueo")
    @Transactional
    public Map<String, Object> desbloquear(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cambiar(UsuarioActual.de(jwt), id, "BLOQUEADO", "LIBRE",
                "Sólo se puede desbloquear un cupo propio que esté bloqueado");
    }

    @PostMapping("/citas/{id}/atencion")
    public CitaDto atender(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return reservas.atender(UsuarioActual.de(jwt), id);
    }

    private Map<String, Object> cambiar(UsuarioActual medico, UUID cupoId, String desde, String hacia, String mensaje) {
        var fila = jdbc.sql("""
                        UPDATE agenda.cupo SET estado = :hacia, version = version + 1
                         WHERE id = :id AND medico_id = :m AND estado = :desde RETURNING inicio""")
                .param("hacia", hacia).param("desde", desde).param("id", cupoId).param("m", medico.id())
                .query(java.time.OffsetDateTime.class).optional();
        if (fila.isEmpty()) {
            throw ApiException.conflicto("CUPO_NO_MODIFICABLE", mensaje);
        }
        cache.evictarTrasCommit(medico.id(), fila.get());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", cupoId);
        r.put("estado", hacia);
        return r;
    }
}
