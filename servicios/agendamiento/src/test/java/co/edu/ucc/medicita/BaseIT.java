package co.edu.ucc.medicita;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import co.edu.ucc.medicita.compartido.Tiempo;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

/** Base de las pruebas de integración: Postgres 16 + Redis + RabbitMQ reales (Testcontainers), un solo contexto. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIT {

    protected static final UUID ROSERO = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    protected static final UUID SEDE_CENTRO = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    protected static final UUID ANA = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    protected static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.16-alpine3.24")
            .withDatabaseName("medicita").withUsername("medicita").withPassword("medicita");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8-management");

    private static final AtomicInteger DIA = new AtomicInteger(400);

    static {
        Startables.deepStart(PG, REDIS, RABBIT).join();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.addresses", () -> RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;

    protected static RequestPostProcessor paciente(UUID sub, String nombre, String documento) {
        return usuario(sub, nombre, documento, null, "paciente");
    }

    protected static RequestPostProcessor usuario(UUID sub, String nombre, String documento, String institucion,
                                                  String rol) {
        return jwt().jwt(j -> {
            j.subject(sub.toString()).claim("name", nombre).claim("telefono", "+573000000000")
                    .claim("realm_access", Map.of("roles", List.of(rol)));
            if (documento != null) {
                j.claim("documento", documento);
            }
            if (institucion != null) {
                j.claim("institucion", institucion);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    /** Inserta cupos LIBRE consecutivos de 20 minutos en un día lejano y único (aislados entre pruebas). */
    protected List<UUID> crearCupos(UUID medico, UUID sede, int cantidad) {
        OffsetDateTime base = Tiempo.ahora().plusDays(DIA.incrementAndGet()).truncatedTo(ChronoUnit.DAYS).plusHours(8);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            UUID id = UUID.randomUUID();
            OffsetDateTime inicio = base.plusMinutes(20L * i);
            jdbc.sql("INSERT INTO agenda.cupo(id, medico_id, sede_id, inicio, fin, estado) VALUES (:id, :m, :s, :i, :f, 'LIBRE')")
                    .param("id", id).param("m", medico).param("s", sede).param("i", inicio)
                    .param("f", inicio.plusMinutes(20)).update();
            ids.add(id);
        }
        return ids;
    }
}
