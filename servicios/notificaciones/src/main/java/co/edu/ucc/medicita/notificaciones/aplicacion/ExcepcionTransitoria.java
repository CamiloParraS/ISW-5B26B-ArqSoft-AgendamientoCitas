package co.edu.ucc.medicita.notificaciones.aplicacion;

/** Falla recuperable: el mensaje va a la cola de reintento. */
public class ExcepcionTransitoria extends RuntimeException {
    public ExcepcionTransitoria(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public ExcepcionTransitoria(String mensaje) {
        super(mensaje);
    }
}
