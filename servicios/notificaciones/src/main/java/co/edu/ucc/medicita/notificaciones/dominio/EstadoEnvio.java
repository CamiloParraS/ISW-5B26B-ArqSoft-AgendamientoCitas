package co.edu.ucc.medicita.notificaciones.dominio;

public enum EstadoEnvio {
    /** El proveedor aceptó el mensaje. */
    ENVIADO,
    /** El proveedor lo rechazó (p. ej. plantilla pausada, número inválido). */
    RECHAZADO,
    /** Falla de red o del proveedor (IO, 5xx). */
    FALLIDO,
    /** Sólo para consultarEstado: el canal no puede informar el estado. */
    DESCONOCIDO
}
