package co.edu.ucc.medicita.notificaciones.aplicacion;

import co.edu.ucc.medicita.notificaciones.dominio.Destinatario;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.Plantillas;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import co.edu.ucc.medicita.notificaciones.persistencia.CitasPorRecordarJdbc;
import co.edu.ucc.medicita.notificaciones.persistencia.CitasPorRecordarJdbc.CitaPorRecordar;
import co.edu.ucc.medicita.notificaciones.persistencia.DestinatariosJdbc;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Recordatorios masivos de las 6 p. m. (hora de Bogotá) para las citas del día siguiente. */
@Service
public class ServicioRecordatorios {

    private static final Logger log = LoggerFactory.getLogger(ServicioRecordatorios.class);

    public record Resumen(LocalDate fecha, int evaluadas, int enviadas, int fallidas) {
    }

    private final CitasPorRecordarJdbc citas;
    private final DestinatariosJdbc destinatarios;
    private final SelectorDeCanales selector;
    private final NotificacionesProps props;
    private final TransactionTemplate tx;

    public ServicioRecordatorios(CitasPorRecordarJdbc citas, DestinatariosJdbc destinatarios,
                                 SelectorDeCanales selector, NotificacionesProps props, TransactionTemplate tx) {
        this.citas = citas;
        this.destinatarios = destinatarios;
        this.selector = selector;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(cron = "${medicita.notificaciones.recordatorios.cron:0 0 18 * * *}",
            zone = "${medicita.notificaciones.zona:America/Bogota}")
    public void ejecutarProgramado() {
        MDC.put("correlacionId", "recordatorios-" + UUID.randomUUID());
        try {
            ejecutar(LocalDate.now(props.zona()).plusDays(1));
        } finally {
            MDC.remove("correlacionId");
        }
    }

    /** Envía RECORDATORIO de las citas que comienzan en la fecha dada (día calendario en America/Bogota). */
    public Resumen ejecutar(LocalDate fecha) {
        OffsetDateTime desde = fecha.atStartOfDay(props.zona()).toOffsetDateTime();
        OffsetDateTime hasta = fecha.plusDays(1).atStartOfDay(props.zona()).toOffsetDateTime();
        String correlacion = MDC.get("correlacionId");
        int evaluadas = 0;
        int enviadas = 0;
        int fallidas = 0;
        for (CitaPorRecordar c : citas.pendientesEntre(desde, hasta)) {
            evaluadas++;
            Boolean ok = tx.execute(estado -> {
                if (!citas.marcarRecordada(c.citaId())) {
                    return null; // otra ejecución ya la recordó
                }
                Optional<Destinatario> d = destinatarios.buscar(c.pacienteId());
                if (d.isEmpty()) {
                    return false;
                }
                Mensaje m = new Mensaje(TipoNotificacion.RECORDATORIO, c.citaId(), d.get(), c.medicoNombre(),
                        Plantillas.fecha(c.inicio(), props.zona()), Plantillas.hora(c.inicio(), props.zona()),
                        c.sedeNombre(), correlacion);
                boolean entregado = selector.entregar(m, null).entregado();
                if (!entregado) {
                    citas.desmarcarRecordada(c.citaId()); // queda pendiente para la próxima ejecución
                }
                return entregado;
            });
            if (Boolean.TRUE.equals(ok)) enviadas++;
            else if (Boolean.FALSE.equals(ok)) fallidas++;
        }
        log.info("Recordatorios para el {}: {} citas evaluadas, {} enviados, {} sin entregar",
                fecha, evaluadas, enviadas, fallidas);
        return new Resumen(fecha, evaluadas, enviadas, fallidas);
    }
}
