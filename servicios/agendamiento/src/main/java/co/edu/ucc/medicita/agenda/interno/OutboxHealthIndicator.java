package co.edu.ucc.medicita.agenda.interno;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Alarma R5: si el evento pendiente más antiguo supera 5 min, estado OUTBOX_ATRASADA (HTTP 200: es una alarma,
 * no una falla de vida; el gateway sigue enrutando).
 */
@Component("outboxHealthIndicator")
class OutboxHealthIndicator implements HealthIndicator {

    static final Status ATRASADA = new Status("OUTBOX_ATRASADA", "El evento pendiente más antiguo supera 5 minutos");

    private final Outbox outbox;

    OutboxHealthIndicator(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public Health health() {
        EstadoOutbox e = outbox.estado();
        Health.Builder b = e.alarma() ? Health.status(ATRASADA) : Health.up();
        return b.withDetail("pendientes", e.pendientes())
                .withDetail("antiguedadMasAntiguaSegundos", e.antiguedadMasAntiguaSegundos()).build();
    }
}
