package co.edu.ucc.medicita.acceso.interno;

import java.nio.charset.StandardCharsets;
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

/** Datos iniciales de Acceso: instituciones aliadas y consentimiento de Ana hacia IPS-NORTE. Idempotente + advisory lock. */
@Component("semillaAcceso")
@Order(30)
@ConditionalOnProperty(name = "medicita.semilla.habilitada", havingValue = "true", matchIfMissing = true)
class Semilla implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Semilla.class);
    private static final long CLAVE_BLOQUEO = 7_003L;

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
            institucion("IPS-NORTE", "Hospital Aliado del Norte");
            institucion("IPS-SUR", "Clínica Aliada del Sur");
            UUID id = UUID.nameUUIDFromBytes("consentimiento-semilla:ana:IPS-NORTE".getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                            INSERT INTO acceso.consentimiento(id, paciente_id, institucion_codigo, otorgado_en, vigente_hasta)
                            VALUES (:id, :p, 'IPS-NORTE', now(), now() + interval '1 year') ON CONFLICT (id) DO NOTHING""")
                    .param("id", id).param("p", UUID.fromString("a0000000-0000-4000-8000-000000000001")).update();
        });
        log.info("Semilla de acceso aplicada");
    }

    private void institucion(String codigo, String nombre) {
        jdbc.sql("INSERT INTO acceso.institucion(codigo, nombre) VALUES (:c, :n) ON CONFLICT (codigo) DO NOTHING")
                .param("c", codigo).param("n", nombre).update();
    }
}
