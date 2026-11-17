package co.edu.ucc.medicita.integracionclinica.eventos;

/** El mensaje no es un evento v1 válido: va directo a la DLQ. */
public class EventoMalformadoException extends RuntimeException {

    public EventoMalformadoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public EventoMalformadoException(String mensaje) {
        super(mensaje);
    }
}
