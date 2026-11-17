package co.edu.ucc.medicita.notificaciones.persistencia;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Idempotencia del consumidor: la inserción comparte transacción con los envíos. */
@Repository
public class EventosProcesadosJdbc {

    private final JdbcClient jdbc;

    public EventosProcesadosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** true si este proceso reclamó el evento; false si ya estaba procesado (duplicado). */
    public boolean reclamar(UUID eventoId) {
        return jdbc.sql("INSERT INTO notificaciones.evento_procesado (evento_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventoId).update() == 1;
    }

    /** Devuelve el evento a "no procesado" para que el reintento lo vuelva a intentar. */
    public void liberar(UUID eventoId) {
        jdbc.sql("DELETE FROM notificaciones.evento_procesado WHERE evento_id = :id").param("id", eventoId).update();
    }
}
