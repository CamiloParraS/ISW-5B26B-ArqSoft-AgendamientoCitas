package co.edu.ucc.medicita.notificaciones.aplicacion;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import java.util.UUID;

/** Puerto de persistencia: cada intento por un canal es una fila en envio. */
public interface RegistroEnvios {

    void registrar(UUID eventoId, UUID citaId, UUID pacienteId, TipoNotificacion tipo, Canal canal,
                   ResultadoEnvio resultado, String correlacionId);
}
