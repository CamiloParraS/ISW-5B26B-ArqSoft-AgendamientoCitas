package co.edu.ucc.medicita.notificaciones.aplicacion;

/** Mensaje mal formado o sin remedio: va directo a la DLQ. */
public class ExcepcionPermanente extends RuntimeException {
    public ExcepcionPermanente(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public ExcepcionPermanente(String mensaje) {
        super(mensaje);
    }
}
