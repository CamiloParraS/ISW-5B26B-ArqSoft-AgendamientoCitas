package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.HorarioPublicado;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Atiende el evento HorarioPublicado de Prestadores: genera los cupos del rango. Idempotente por
 * UNIQUE(medico_id, inicio) (ON CONFLICT DO NOTHING).
 */
@Component
class GeneradorCupos {

    private static final Logger log = LoggerFactory.getLogger(GeneradorCupos.class);

    private final JdbcTemplate jdbc;
    private final CacheCupos cache;

    GeneradorCupos(JdbcTemplate jdbc, CacheCupos cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @ApplicationModuleListener
    public void alPublicarseHorario(HorarioPublicado h) {
        List<Object[]> filas = new ArrayList<>();
        List<LocalDate> dias = new ArrayList<>();
        for (LocalDate dia = h.desde(); !dia.isAfter(h.hasta()); dia = dia.plusDays(1)) {
            if (!h.diasSemana().contains(dia.getDayOfWeek().getValue())) {
                continue;
            }
            dias.add(dia);
            for (LocalTime t = h.horaInicio();
                 !t.plusMinutes(h.duracionMinutos()).isAfter(h.horaFin()) && !t.plusMinutes(h.duracionMinutos()).isBefore(t);
                 t = t.plusMinutes(h.duracionMinutos())) {
                var inicio = LocalDateTime.of(dia, t).atZone(Tiempo.ZONA).toOffsetDateTime();
                var fin = inicio.plusMinutes(h.duracionMinutos());
                filas.add(new Object[]{UUID.randomUUID(), h.medicoId(), h.sedeId(), inicio, fin});
            }
        }
        int[][] resultado = jdbc.batchUpdate("""
                        INSERT INTO agenda.cupo(id, medico_id, sede_id, inicio, fin, estado)
                        VALUES (?, ?, ?, ?, ?, 'LIBRE') ON CONFLICT (medico_id, inicio) DO NOTHING""",
                filas, 500, (ps, f) -> {
                    ps.setObject(1, f[0]);
                    ps.setObject(2, f[1]);
                    ps.setObject(3, f[2]);
                    ps.setObject(4, f[3]);
                    ps.setObject(5, f[4]);
                });
        long nuevos = java.util.Arrays.stream(resultado).flatMapToInt(java.util.Arrays::stream).filter(r -> r > 0).count();
        log.info("Horario {} publicado: {} cupos generados para el médico {}", h.horarioId(), nuevos, h.medicoId());
        dias.forEach(d -> cache.evictarTrasCommit(h.medicoId(), Tiempo.inicioDelDia(d)));
    }
}
