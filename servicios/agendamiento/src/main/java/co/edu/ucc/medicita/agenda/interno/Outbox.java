package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.CorrelacionFiltro;
import co.edu.ucc.medicita.compartido.Tiempo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Escritura y consulta de la tabla outbox (evento + reserva en la misma transacción). */
@Component
class Outbox {

    static final String EXCHANGE = "medicita.citas";
    static final long UMBRAL_ALARMA_SEGUNDOS = 300;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    Outbox(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserta el evento con el sobre v1 (eventoId = id de la fila). Debe llamarse dentro de la transacción de negocio. */
    UUID registrar(String tipo, String routingKey, Map<String, Object> datos) {
        UUID id = UUID.randomUUID();
        String correlacion = CorrelacionFiltro.actual();
        Map<String, Object> sobre = new LinkedHashMap<>();
        sobre.put("eventoId", id.toString());
        sobre.put("tipo", tipo);
        sobre.put("version", 1);
        sobre.put("ocurridoEn", Tiempo.iso(Tiempo.ahora()));
        sobre.put("correlacionId", correlacion);
        sobre.put("datos", datos);
        try {
            jdbc.sql("""
                            INSERT INTO agenda.outbox(id, tipo, routing_key, carga, correlacion_id)
                            VALUES (:id, :tipo, :rk, CAST(:carga AS jsonb), :corr)""")
                    .param("id", id).param("tipo", tipo).param("rk", routingKey)
                    .param("carga", json.writeValueAsString(sobre)).param("corr", correlacion).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento " + tipo, e);
        }
        return id;
    }

    EstadoOutbox estado() {
        return jdbc.sql("""
                        SELECT count(*) AS n,
                               COALESCE(EXTRACT(EPOCH FROM (now() - min(creado_en))), 0)::bigint AS edad
                        FROM agenda.outbox WHERE publicado_en IS NULL""")
                .query((rs, i) -> {
                    long edad = rs.getLong("edad");
                    return new EstadoOutbox(rs.getLong("n"), edad, edad > UMBRAL_ALARMA_SEGUNDOS);
                }).single();
    }
}
