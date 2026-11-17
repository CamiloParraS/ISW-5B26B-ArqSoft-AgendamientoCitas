package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import co.edu.ucc.medicita.prestadores.MedicoResumen;
import co.edu.ucc.medicita.prestadores.SedeResumen;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso SQL a agenda.cupo (sólo tablas del esquema agenda). */
@Repository
class Cupos {

    private static final String COLUMNAS = "id, medico_id, sede_id, inicio, fin, estado, retenido_por, retenido_hasta";

    private final JdbcClient jdbc;
    private final CatalogoPrestadores catalogo;

    Cupos(JdbcClient jdbc, CatalogoPrestadores catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    Optional<CupoRow> porId(UUID id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cupo WHERE id = :id").param("id", id)
                .query(Cupos::mapear).optional();
    }

    /** Cupos LIBRE de un médico en un día calendario de Bogotá (lo que se guarda en la caché). */
    List<CupoRow> libresDelDia(UUID medicoId, LocalDate fecha) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cupo WHERE medico_id = :m AND estado = 'LIBRE' "
                        + "AND inicio >= :d AND inicio < :h ORDER BY inicio")
                .param("m", medicoId).param("d", Tiempo.inicioDelDia(fecha)).param("h", Tiempo.inicioDelDia(fecha.plusDays(1)))
                .query(Cupos::mapear).list();
    }

    /** Primeros cupos libres desde ahora entre los médicos dados. */
    List<CupoRow> proximos(Collection<UUID> medicos, int limite) {
        if (medicos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cupo WHERE medico_id IN (:ids) AND estado = 'LIBRE' "
                        + "AND inicio > now() ORDER BY inicio, medico_id LIMIT :l")
                .param("ids", medicos).param("l", limite).query(Cupos::mapear).list();
    }

    /** Siguientes cupos libres del mismo médico desde una hora (alternativas ante un 409). */
    List<CupoRow> alternativas(UUID medicoId, OffsetDateTime desde, UUID excluir, int limite) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM agenda.cupo WHERE medico_id = :m AND estado = 'LIBRE' "
                        + "AND inicio >= :d AND inicio > now() AND id <> :x ORDER BY inicio LIMIT :l")
                .param("m", medicoId).param("d", desde).param("x", excluir).param("l", limite)
                .query(Cupos::mapear).list();
    }

    /** Alternativas de un cupo perdido, ya como DTO. */
    List<CupoDto> alternativasDto(CupoRow perdido) {
        return aDtos(alternativas(perdido.medicoId(), perdido.inicio(), perdido.id(), 5));
    }

    List<CupoDto> aDtos(List<CupoRow> filas) {
        Map<UUID, MedicoResumen> medicos = new HashMap<>();
        Map<UUID, SedeResumen> sedes = new HashMap<>();
        return filas.stream().map(f -> {
            MedicoResumen m = medicos.computeIfAbsent(f.medicoId(), id -> catalogo.medico(id).orElse(null));
            SedeResumen s = sedes.computeIfAbsent(f.sedeId(), id -> catalogo.sede(id).orElse(null));
            return new CupoDto(f.id(), f.medicoId(), m == null ? null : m.nombre(), m == null ? null : m.especialidad(),
                    f.sedeId(), s == null ? null : s.nombre(), f.inicio(), f.fin());
        }).toList();
    }

    private static CupoRow mapear(ResultSet rs, int n) throws SQLException {
        return new CupoRow(rs.getObject("id", UUID.class), rs.getObject("medico_id", UUID.class),
                rs.getObject("sede_id", UUID.class), rs.getObject("inicio", OffsetDateTime.class),
                rs.getObject("fin", OffsetDateTime.class), rs.getString("estado"),
                rs.getObject("retenido_por", UUID.class), rs.getObject("retenido_hasta", OffsetDateTime.class));
    }
}

record CupoRow(UUID id, UUID medicoId, UUID sedeId, OffsetDateTime inicio, OffsetDateTime fin, String estado,
               UUID retenidoPor, OffsetDateTime retenidoHasta) {
}
