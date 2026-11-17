package co.edu.ucc.medicita.agenda.interno;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Datos iniciales de Agenda: políticas de cancelación (24 h) y pacientes de demostración. Idempotente + advisory lock. */
@Component("semillaAgenda")
@Order(20)
@ConditionalOnProperty(name = "medicita.semilla.habilitada", havingValue = "true", matchIfMissing = true)
class Semilla implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Semilla.class);
    private static final long CLAVE_BLOQUEO = 7_002L;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    Semilla(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(estado -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CLAVE_BLOQUEO).query((rs, n) -> 1).list();
            politica("c0000000-0000-4000-8000-000000000001", 24); // Sede Centro
            politica("c0000000-0000-4000-8000-000000000002", 24); // Sede Norte
            paciente("a0000000-0000-4000-8000-000000000001", "1085000001", "Ana Gómez", "+573001110001");
            paciente("a0000000-0000-4000-8000-000000000002", "1085000002", "Luis Pérez", "+573001110002");
        });
        log.info("Semilla de agenda aplicada");
    }

    private void politica(String sede, int horas) {
        jdbc.sql("INSERT INTO agenda.politica_cancelacion(sede_id, horas_minimas) VALUES (:s, :h) ON CONFLICT (sede_id) DO NOTHING")
                .param("s", UUID.fromString(sede)).param("h", horas).update();
    }

    private void paciente(String id, String documento, String nombre, String telefono) {
        jdbc.sql("INSERT INTO agenda.paciente(id, documento, nombre, telefono) VALUES (:id, :d, :n, :t) ON CONFLICT DO NOTHING")
                .param("id", UUID.fromString(id)).param("d", documento).param("n", nombre).param("t", telefono).update();
    }
}
