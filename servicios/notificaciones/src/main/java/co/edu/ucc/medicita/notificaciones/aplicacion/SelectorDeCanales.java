package co.edu.ucc.medicita.notificaciones.aplicacion;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.CanalNotificacion;
import co.edu.ucc.medicita.notificaciones.dominio.Destinatario;
import co.edu.ucc.medicita.notificaciones.dominio.EstadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Microkernel local (ADR-003): descubre los adaptadores habilitados como lista de beans
 * y aplica la política canal preferido, luego respaldo WHATSAPP, SMS, CORREO.
 */
@Component
public class SelectorDeCanales {

    private static final Logger log = LoggerFactory.getLogger(SelectorDeCanales.class);

    private final Map<Canal, CanalNotificacion> adaptadores = new LinkedHashMap<>();
    private final List<Canal> respaldo;
    private final RegistroEnvios registro;
    private final Environment entorno;

    public SelectorDeCanales(List<CanalNotificacion> canales, NotificacionesProps props, RegistroEnvios registro,
                           Environment entorno) {
        canales.forEach(c -> adaptadores.put(c.canal(), c));
        this.respaldo = props.respaldo().stream().map(Canal::of).toList();
        this.entorno = entorno;
        this.registro = registro;
        log.info("Canales habilitados: {}; orden de respaldo: {}", adaptadores.keySet(), respaldo);
    }

    /** Orden de intento: preferido primero, luego el respaldo; omite canales deshabilitados o sin dato de contacto. */
    public List<Canal> ordenDeIntento(Destinatario d) {
        Set<Canal> orden = new LinkedHashSet<>();
        if (d.canalPreferido() != null) orden.add(d.canalPreferido());
        orden.addAll(respaldo);
        List<Canal> resultado = new ArrayList<>();
        for (Canal c : orden) {
            if (adaptadores.containsKey(c) && tieneContacto(c, d)) resultado.add(c);
        }
        return resultado;
    }

    /**
     * El dato de contacto que necesita cada canal se configura en
     * medicita.notificaciones.canales.<canal>.contacto = telefono | email (por defecto telefono).
     */
    private boolean tieneContacto(Canal canal, Destinatario d) {
        String contacto = entorno.getProperty(
                "medicita.notificaciones.canales." + canal.codigo().toLowerCase() + ".contacto", "telefono");
        String valor = "email".equalsIgnoreCase(contacto) ? d.email() : d.telefono();
        return valor != null && !valor.isBlank();
    }

    /** Intenta canal por canal, registra cada intento y se detiene en el primer ENVIADO. */
    public Entrega entregar(Mensaje mensaje, UUID eventoId) {
        Destinatario d = mensaje.destinatario();
        List<Canal> orden = ordenDeIntento(d);
        List<Intento> intentos = new ArrayList<>();
        for (Canal canal : orden) {
            ResultadoEnvio r;
            try {
                r = adaptadores.get(canal).enviar(mensaje);
            } catch (RuntimeException e) {
                r = ResultadoEnvio.fallido("Excepción del adaptador: " + e.getMessage());
            }
            registro.registrar(eventoId, mensaje.citaId(), d.pacienteId(), mensaje.tipo(), canal, r, mensaje.correlacionId());
            intentos.add(new Intento(canal, r));
            log.info("Envío {} de la cita {} por {}: {}", mensaje.tipo(), mensaje.citaId(), canal, r.estado());
            if (r.estado() == EstadoEnvio.ENVIADO) {
                return new Entrega(true, intentos);
            }
        }
        if (orden.isEmpty()) {
            log.warn("Sin canal disponible para el paciente {}", d.pacienteId());
        }
        return new Entrega(false, intentos);
    }

    public record Intento(Canal canal, ResultadoEnvio resultado) {
    }

    public record Entrega(boolean entregado, List<Intento> intentos) {
    }
}
