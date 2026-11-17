package co.edu.ucc.medicita.compartido;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Error de negocio con cuerpo JSON {"codigo","mensaje", ...extra}. */
public class ApiException extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public ApiException(HttpStatus estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    public ApiException con(String clave, Object valor) {
        extra.put(clave, valor);
        return this;
    }

    public HttpStatus estado() {
        return estado;
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, Object> cuerpo() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("codigo", codigo);
        cuerpo.put("mensaje", getMessage());
        cuerpo.putAll(extra);
        return cuerpo;
    }

    public static ApiException solicitudInvalida(String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", mensaje);
    }

    public static ApiException noEncontrado(String codigo, String mensaje) {
        return new ApiException(HttpStatus.NOT_FOUND, codigo, mensaje);
    }

    public static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }
}
