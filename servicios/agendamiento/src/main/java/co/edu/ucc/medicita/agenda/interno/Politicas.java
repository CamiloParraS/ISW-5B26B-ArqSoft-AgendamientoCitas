package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Política de cancelación por sede. Se lee de la base de datos en cada solicitud: cambiar las horas mínimas
 * surte efecto de inmediato, sin redesplegar.
 */
@Service
class Politicas {

    static final int HORAS_POR_DEFECTO = 24;

    private final JdbcClient jdbc;
    private final CatalogoPrestadores catalogo;

    Politicas(JdbcClient jdbc, CatalogoPrestadores catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    public int horasMinimas(UUID sedeId) {
        return jdbc.sql("SELECT horas_minimas FROM agenda.politica_cancelacion WHERE sede_id = :s")
                .param("s", sedeId).query(Integer.class).optional().orElse(HORAS_POR_DEFECTO);
    }

    @Transactional(readOnly = true)
    public List<PoliticaDto> listar() {
        return jdbc.sql("SELECT sede_id, horas_minimas, actualizada_en FROM agenda.politica_cancelacion")
                .query((rs, n) -> {
                    UUID sede = rs.getObject("sede_id", UUID.class);
                    String nombre = catalogo.sede(sede).map(s -> s.nombre()).orElse(null);
                    return new PoliticaDto(sede, nombre, rs.getInt("horas_minimas"),
                            rs.getObject("actualizada_en", OffsetDateTime.class));
                }).list().stream().sorted(java.util.Comparator.comparing(p -> String.valueOf(p.sedeNombre()))).toList();
    }

    @Transactional
    public PoliticaDto actualizar(UUID sedeId, Integer horas) {
        if (horas == null || horas < 0 || horas > 24 * 30) {
            throw ApiException.solicitudInvalida("horasMinimas debe ser un entero entre 0 y 720");
        }
        var sede = catalogo.sede(sedeId)
                .orElseThrow(() -> ApiException.noEncontrado("SEDE_NO_ENCONTRADA", "La sede indicada no existe"));
        OffsetDateTime ahora = jdbc.sql("""
                        INSERT INTO agenda.politica_cancelacion(sede_id, horas_minimas, actualizada_en)
                        VALUES (:s, :h, now())
                        ON CONFLICT (sede_id) DO UPDATE SET horas_minimas = EXCLUDED.horas_minimas, actualizada_en = now()
                        RETURNING actualizada_en""")
                .param("s", sedeId).param("h", horas).query(OffsetDateTime.class).single();
        return new PoliticaDto(sedeId, sede.nombre(), horas, ahora);
    }
}
