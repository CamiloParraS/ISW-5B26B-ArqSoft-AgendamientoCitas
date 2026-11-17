package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reporte mensual de ocupación por médico, leído de la RÉPLICA (datasource de sólo lectura). */
@Service
class ServicioReporteOcupacion {

    private final JdbcClient replica;
    private final CatalogoPrestadores catalogo;

    ServicioReporteOcupacion(@Qualifier("replicaDataSource") DataSource replica, CatalogoPrestadores catalogo) {
        this.replica = JdbcClient.create(replica);
        this.catalogo = catalogo;
    }

    ReporteOcupacion generar(String mes) {
        YearMonth ym;
        try {
            ym = mes == null || mes.isBlank() ? YearMonth.now(Tiempo.ZONA) : YearMonth.parse(mes);
        } catch (DateTimeParseException e) {
            throw ApiException.solicitudInvalida("El mes debe tener el formato YYYY-MM");
        }
        OffsetDateTime desde = Tiempo.inicioDelDia(ym.atDay(1));
        OffsetDateTime hasta = Tiempo.inicioDelDia(ym.plusMonths(1).atDay(1));
        List<OcupacionMedico> medicos = replica.sql("""
                        SELECT c.medico_id,
                               count(*) AS total,
                               count(*) FILTER (WHERE c.estado = 'RESERVADO') AS reservados,
                               count(ci.id) FILTER (WHERE ci.estado = 'ATENDIDA') AS atendidos
                          FROM agenda.cupo c
                          LEFT JOIN agenda.cita ci ON ci.cupo_id = c.id AND ci.estado <> 'CANCELADA'
                         WHERE c.inicio >= :d AND c.inicio < :h
                         GROUP BY c.medico_id""")
                .param("d", desde).param("h", hasta)
                .query((rs, n) -> {
                    UUID medicoId = rs.getObject("medico_id", UUID.class);
                    long total = rs.getLong("total");
                    long reservados = rs.getLong("reservados");
                    double pct = total == 0 ? 0 : Math.round(reservados * 10000.0 / total) / 100.0;
                    return new OcupacionMedico(medicoId, catalogo.medico(medicoId).map(m -> m.nombre()).orElse(null),
                            total, reservados, rs.getLong("atendidos"), pct);
                }).list().stream()
                .sorted(java.util.Comparator.comparing(m -> String.valueOf(m.medicoNombre()))).toList();
        return new ReporteOcupacion(ym.toString(), medicos);
    }
}
