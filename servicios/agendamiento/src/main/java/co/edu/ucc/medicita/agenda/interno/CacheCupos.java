package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Caché de cupos libres por médico y día (Redis). Clave cupos-libres:{medicoId}:{yyyy-MM-dd}, TTL 60 s.
 * Sólo acelera la búsqueda: la confirmación nunca la lee. Ante cualquier error de Redis se registra y se
 * sigue con la base de datos (manejador de errores de caché: log + paso al origen).
 */
@Component
class CacheCupos {

    private static final Logger log = LoggerFactory.getLogger(CacheCupos.class);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long PAUSA_TRAS_ERROR_MS = 5_000;
    private static final TypeReference<List<CupoDto>> LISTA = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private volatile long pausadaHasta = 0;

    CacheCupos(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    static String clave(UUID medicoId, LocalDate fecha) {
        return "cupos-libres:" + medicoId + ":" + fecha;
    }

    Optional<List<CupoDto>> obtener(UUID medicoId, LocalDate fecha) {
        if (pausada()) {
            return Optional.empty();
        }
        try {
            String valor = redis.opsForValue().get(clave(medicoId, fecha));
            return valor == null ? Optional.empty() : Optional.of(json.readValue(valor, LISTA));
        } catch (Exception e) {
            falla("lectura", e);
            return Optional.empty();
        }
    }

    void guardar(UUID medicoId, LocalDate fecha, List<CupoDto> cupos) {
        if (pausada()) {
            return;
        }
        try {
            redis.opsForValue().set(clave(medicoId, fecha), json.writeValueAsString(cupos), TTL);
        } catch (Exception e) {
            falla("escritura", e);
        }
    }

    /** Invalida la entrada del día del cupo, después del commit si hay transacción en curso. */
    void evictarTrasCommit(UUID medicoId, OffsetDateTime inicioCupo) {
        LocalDate fecha = Tiempo.bogota(inicioCupo).toLocalDate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictar(medicoId, fecha);
                }
            });
        } else {
            evictar(medicoId, fecha);
        }
    }

    void evictar(UUID medicoId, LocalDate fecha) {
        try {
            redis.delete(clave(medicoId, fecha));
        } catch (Exception e) {
            // Sin Redis no hay nada que invalidar y el TTL de 60 s acota el desfase.
            falla("invalidación", e);
        }
    }

    private boolean pausada() {
        return System.currentTimeMillis() < pausadaHasta;
    }

    private void falla(String operacion, Exception e) {
        pausadaHasta = System.currentTimeMillis() + PAUSA_TRAS_ERROR_MS;
        log.warn("Caché de cupos no disponible ({}), se usa la base de datos: {}", operacion, e.toString());
    }
}
