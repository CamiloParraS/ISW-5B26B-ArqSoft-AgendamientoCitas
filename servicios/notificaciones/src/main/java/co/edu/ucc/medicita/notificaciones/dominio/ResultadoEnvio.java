package co.edu.ucc.medicita.notificaciones.dominio;

public record ResultadoEnvio(EstadoEnvio estado, String idExterno, String detalle) {

    public static ResultadoEnvio enviado(String idExterno) {
        return new ResultadoEnvio(EstadoEnvio.ENVIADO, idExterno, null);
    }

    public static ResultadoEnvio rechazado(String detalle) {
        return new ResultadoEnvio(EstadoEnvio.RECHAZADO, null, detalle);
    }

    public static ResultadoEnvio fallido(String detalle) {
        return new ResultadoEnvio(EstadoEnvio.FALLIDO, null, detalle);
    }
}
