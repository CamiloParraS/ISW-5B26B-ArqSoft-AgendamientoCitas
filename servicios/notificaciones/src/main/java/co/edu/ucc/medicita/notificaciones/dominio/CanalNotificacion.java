package co.edu.ucc.medicita.notificaciones.dominio;

/**
 * Puerto de salida (ADR-003). Un canal nuevo es un adaptador que implementa esta interfaz
 * y se activa por configuración; nada más cambia. Los adaptadores no lanzan excepciones
 * por fallas del proveedor: devuelven RECHAZADO o FALLIDO.
 */
public interface CanalNotificacion {

    Canal canal();

    ResultadoEnvio enviar(Mensaje mensaje);

    EstadoEnvio consultarEstado(String idExterno);
}
