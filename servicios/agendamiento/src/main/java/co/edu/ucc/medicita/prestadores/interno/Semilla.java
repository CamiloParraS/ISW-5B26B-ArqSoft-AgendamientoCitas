package co.edu.ucc.medicita.prestadores.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.HorarioPublicado;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Datos iniciales de la red de prestadores. Idempotente (ON CONFLICT DO NOTHING) y serializada con un
 * advisory lock de Postgres para que dos instancias que arrancan juntas no dupliquen nada.
 * Los horarios nuevos se publican con HorarioPublicado; Agenda genera los cupos.
 */
@Component("semillaPrestadores")
@Order(10)
@ConditionalOnProperty(name = "medicita.semilla.habilitada", havingValue = "true", matchIfMissing = true)
class Semilla implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Semilla.class);
    private static final long CLAVE_BLOQUEO = 7_001L;

    static final UUID SEDE_CENTRO = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final UUID SEDE_NORTE = UUID.fromString("c0000000-0000-4000-8000-000000000002");
    static final UUID MEDICINA_GENERAL = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    static final UUID PEDIATRIA = UUID.fromString("f0000000-0000-4000-8000-000000000002");
    static final UUID CARDIOLOGIA = UUID.fromString("f0000000-0000-4000-8000-000000000003");
    static final UUID ROSERO = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    static final UUID BURBANO = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final UUID ORTIZ = UUID.fromString("b0000000-0000-4000-8000-000000000003");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher eventos;

    Semilla(JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher eventos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.eventos = eventos;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(estado -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CLAVE_BLOQUEO).query((rs, n) -> 1).list();
            sede(SEDE_CENTRO, "Sede Centro", "Calle 18 # 25-40, Pasto");
            sede(SEDE_NORTE, "Sede Norte", "Av. Panamericana # 12-30, Pasto");
            especialidad(MEDICINA_GENERAL, "Medicina general");
            especialidad(PEDIATRIA, "Pediatría");
            especialidad(CARDIOLOGIA, "Cardiología");
            medico(ROSERO, "Carolina Rosero", MEDICINA_GENERAL, SEDE_CENTRO);
            medico(BURBANO, "Andrés Burbano", PEDIATRIA, SEDE_NORTE);
            medico(ORTIZ, "Jorge Ortiz", CARDIOLOGIA, SEDE_CENTRO);

            LocalDate hoy = Tiempo.hoy();
            int publicados = 0;
            for (var m : List.of(new Object[]{ROSERO, SEDE_CENTRO}, new Object[]{BURBANO, SEDE_NORTE},
                    new Object[]{ORTIZ, SEDE_CENTRO})) {
                UUID medicoId = (UUID) m[0];
                UUID sedeId = (UUID) m[1];
                publicados += horario(medicoId, sedeId, "manana", hoy, LocalTime.of(7, 0), LocalTime.of(12, 0)) ? 1 : 0;
                publicados += horario(medicoId, sedeId, "tarde", hoy, LocalTime.of(14, 0), LocalTime.of(17, 0)) ? 1 : 0;
            }
            log.info("Semilla de prestadores aplicada; horarios publicados en este arranque: {}", publicados);
        });
    }

    private void sede(UUID id, String nombre, String direccion) {
        jdbc.sql("INSERT INTO prestadores.sede(id, nombre, direccion) VALUES (:id, :n, :d) ON CONFLICT (id) DO NOTHING")
                .param("id", id).param("n", nombre).param("d", direccion).update();
    }

    private void especialidad(UUID id, String nombre) {
        jdbc.sql("INSERT INTO prestadores.especialidad(id, nombre) VALUES (:id, :n) ON CONFLICT (id) DO NOTHING")
                .param("id", id).param("n", nombre).update();
    }

    private void medico(UUID id, String nombre, UUID especialidad, UUID sede) {
        jdbc.sql("INSERT INTO prestadores.medico(id, nombre, especialidad_id, sede_id) VALUES (:id, :n, :e, :s) ON CONFLICT (id) DO NOTHING")
                .param("id", id).param("n", nombre).param("e", especialidad).param("s", sede).update();
    }

    /** Publica el horario de los próximos 21 días (lunes a viernes) sólo la primera vez. */
    private boolean horario(UUID medicoId, UUID sedeId, String franja, LocalDate hoy, LocalTime inicio, LocalTime fin) {
        UUID id = UUID.nameUUIDFromBytes(("horario-semilla:" + medicoId + ":" + franja).getBytes(StandardCharsets.UTF_8));
        LocalDate hasta = hoy.plusDays(20);
        int filas = jdbc.sql("""
                        INSERT INTO prestadores.horario(id, medico_id, sede_id, desde, hasta, dias_semana, hora_inicio, hora_fin, duracion_minutos)
                        VALUES (:id, :m, :s, :d, :h, '1,2,3,4,5', :i, :f, 20) ON CONFLICT (id) DO NOTHING""")
                .param("id", id).param("m", medicoId).param("s", sedeId).param("d", hoy).param("h", hasta)
                .param("i", inicio).param("f", fin).update();
        if (filas == 1) {
            eventos.publishEvent(new HorarioPublicado(id, medicoId, sedeId, hoy, hasta, List.of(1, 2, 3, 4, 5),
                    inicio, fin, 20));
            return true;
        }
        return false;
    }
}
