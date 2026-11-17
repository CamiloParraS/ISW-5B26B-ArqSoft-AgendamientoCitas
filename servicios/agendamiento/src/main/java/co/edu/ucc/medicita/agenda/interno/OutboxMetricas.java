package co.edu.ucc.medicita.agenda.interno;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Gauge medicita.outbox.pendiente.antiguedad.segundos (alarma R5) y medicita.outbox.pendientes. */
@Component
class OutboxMetricas {

    OutboxMetricas(MeterRegistry registro, Outbox outbox) {
        Gauge.builder("medicita.outbox.pendiente.antiguedad.segundos", outbox, o -> leer(o, true))
                .description("Antigüedad en segundos del evento más antiguo sin publicar").register(registro);
        Gauge.builder("medicita.outbox.pendientes", outbox, o -> leer(o, false))
                .description("Eventos de la outbox sin publicar").register(registro);
    }

    private static double leer(Outbox outbox, boolean antiguedad) {
        try {
            EstadoOutbox e = outbox.estado();
            return antiguedad ? e.antiguedadMasAntiguaSegundos() : e.pendientes();
        } catch (Exception ex) {
            return Double.NaN;
        }
    }
}
