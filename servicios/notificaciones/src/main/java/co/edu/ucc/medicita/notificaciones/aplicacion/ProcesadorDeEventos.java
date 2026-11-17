package co.edu.ucc.medicita.notificaciones.aplicacion;

import co.edu.ucc.medicita.notificaciones.dominio.Destinatario;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.Plantillas;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import co.edu.ucc.medicita.notificaciones.persistencia.CitasPorRecordarJdbc;
import co.edu.ucc.medicita.notificaciones.persistencia.DestinatariosJdbc;
import co.edu.ucc.medicita.notificaciones.persistencia.EventosProcesadosJdbc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa los eventos v1 de citas. Idempotente por eventoId: la fila de evento_procesado y los envíos
 * se confirman en la misma transacción.
 */
@Service
public class ProcesadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorDeEventos.class);

    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final EventosProcesadosJdbc eventos;
    private final DestinatariosJdbc destinatarios;
    private final CitasPorRecordarJdbc citas;
    private final SelectorDeCanales selector;
    private final NotificacionesProps props;

    public ProcesadorDeEventos(ObjectMapper json, TransactionTemplate tx, EventosProcesadosJdbc eventos,
                               DestinatariosJdbc destinatarios, CitasPorRecordarJdbc citas,
                               SelectorDeCanales selector, NotificacionesProps props) {
        this.json = json;
        this.tx = tx;
        this.eventos = eventos;
        this.destinatarios = destinatarios;
        this.citas = citas;
        this.selector = selector;
        this.props = props;
    }

    private record Evento(UUID eventoId, String tipo, String correlacionId, UUID citaId, UUID pacienteId,
                          String medico, String sede, OffsetDateTime inicio) {
    }

    /**
     * @throws ExcepcionPermanente si el mensaje está mal formado
     * @throws ExcepcionTransitoria si ningún canal pudo entregar (el consumidor lo manda a reintento)
     */
    public void procesar(byte[] cuerpo, String correlacionCabecera) {
        Evento e = leer(cuerpo, correlacionCabecera);
        TipoNotificacion tipo = "CitaAgendada".equals(e.tipo()) ? TipoNotificacion.CONFIRMACION
                : TipoNotificacion.CANCELACION;

        Boolean resuelto = tx.execute(estado -> {
            if (!eventos.reclamar(e.eventoId())) {
                log.info("Evento {} duplicado; se ignora", e.eventoId());
                return true;
            }
            if (tipo == TipoNotificacion.CONFIRMACION) {
                citas.guardar(e.citaId(), e.pacienteId(), e.inicio(), e.medico(), e.sede());
            } else {
                citas.eliminar(e.citaId());
            }
            Optional<Destinatario> destinatario = destinatarios.buscar(e.pacienteId());
            if (destinatario.isEmpty()) {
                log.warn("El paciente {} no tiene destinatario registrado; no hay a quién avisar", e.pacienteId());
                return true;
            }
            Mensaje mensaje = new Mensaje(tipo, e.citaId(), destinatario.get(), e.medico(),
                    Plantillas.fecha(e.inicio(), props.zona()), Plantillas.hora(e.inicio(), props.zona()),
                    e.sede(), e.correlacionId());
            if (selector.entregar(mensaje, e.eventoId()).entregado()) {
                return true;
            }
            // Ningún canal entregó: los intentos quedan registrados, pero el evento sigue sin procesar
            eventos.liberar(e.eventoId());
            return false;
        });
        if (!Boolean.TRUE.equals(resuelto)) {
            throw new ExcepcionTransitoria("Ningún canal pudo entregar el aviso del evento " + e.eventoId());
        }
    }

    private Evento leer(byte[] cuerpo, String correlacionCabecera) {
        JsonNode raiz;
        try {
            raiz = json.readTree(cuerpo);
        } catch (IOException ex) {
            throw new ExcepcionPermanente("JSON inválido", ex);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new ExcepcionPermanente("El evento no es un objeto JSON");
        }
        if (raiz.path("version").asInt(-1) != 1) {
            throw new ExcepcionPermanente("Versión de evento no soportada: " + raiz.path("version"));
        }
        String tipo = texto(raiz, "tipo");
        if (!"CitaAgendada".equals(tipo) && !"CitaCancelada".equals(tipo)) {
            throw new ExcepcionPermanente("Tipo de evento no soportado: " + tipo);
        }
        JsonNode datos = raiz.path("datos");
        String correlacion = correlacionCabecera != null ? correlacionCabecera
                : raiz.path("correlacionId").asText(null);
        return new Evento(uuid(raiz, "eventoId"), tipo, correlacion, uuid(datos, "citaId"),
                uuid(datos, "pacienteId"), texto(datos, "medicoNombre"), texto(datos, "sedeNombre"),
                instante(datos, "inicio"));
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.path(campo);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new ExcepcionPermanente("Falta el campo obligatorio '" + campo + "'");
        }
        return v.asText();
    }

    private static UUID uuid(JsonNode n, String campo) {
        try {
            return UUID.fromString(texto(n, campo));
        } catch (IllegalArgumentException ex) {
            throw new ExcepcionPermanente("El campo '" + campo + "' no es un UUID", ex);
        }
    }

    private static OffsetDateTime instante(JsonNode n, String campo) {
        try {
            return OffsetDateTime.parse(texto(n, campo));
        } catch (DateTimeParseException ex) {
            throw new ExcepcionPermanente("El campo '" + campo + "' no es una fecha ISO-8601", ex);
        }
    }
}
