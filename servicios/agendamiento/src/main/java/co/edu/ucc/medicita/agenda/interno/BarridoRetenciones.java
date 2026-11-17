package co.edu.ucc.medicita.agenda.interno;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Cada 30 s devuelve a LIBRE los cupos con retención vencida (si no tienen cita viva) e invalida la caché. */
@Component
public class BarridoRetenciones {

    private static final Logger log = LoggerFactory.getLogger(BarridoRetenciones.class);

    private final JdbcClient jdbc;
    private final CacheCupos cache;

    BarridoRetenciones(JdbcClient jdbc, CacheCupos cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void ciclo() {
        try {
            barrer();
        } catch (Exception e) {
            log.warn("Barrido de retenciones fallido: {}", e.toString());
        }
    }

    @Transactional
    public int barrer() {
        List<Object[]> liberados = jdbc.sql("""
                        UPDATE agenda.cupo c
                           SET estado = 'LIBRE', retenido_por = NULL, retenido_hasta = NULL, version = version + 1
                         WHERE c.estado = 'RETENIDO' AND c.retenido_hasta < now()
                           AND NOT EXISTS (SELECT 1 FROM agenda.cita ci WHERE ci.cupo_id = c.id AND ci.estado <> 'CANCELADA')
                        RETURNING c.medico_id, c.inicio""")
                .query((rs, n) -> new Object[]{rs.getObject("medico_id", UUID.class),
                        rs.getObject("inicio", OffsetDateTime.class)})
                .list();
        liberados.forEach(l -> cache.evictarTrasCommit((UUID) l[0], (OffsetDateTime) l[1]));
        if (!liberados.isEmpty()) {
            log.info("Retenciones vencidas liberadas: {}", liberados.size());
        }
        return liberados.size();
    }
}
