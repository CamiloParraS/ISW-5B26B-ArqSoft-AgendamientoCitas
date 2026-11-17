package co.edu.ucc.medicita.compartido;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduce toda excepción a JSON {"codigo","mensaje"} en español. */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException e) {
        return ResponseEntity.status(e.estado()).body(e.cuerpo());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> denegado(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(cuerpo("PROHIBIDO", "No tiene permiso para esta operación"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integridad(DataIntegrityViolationException e) {
        log.warn("Violación de integridad: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(cuerpo("CONFLICTO_DE_DATOS", "La operación entra en conflicto con datos existentes"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> inesperado(Exception e) {
        log.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(cuerpo("ERROR_INTERNO", "Ocurrió un error inesperado, intente de nuevo"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        int codigo = status.value();
        Map<String, Object> cuerpo = switch (codigo) {
            case 400 -> cuerpo("SOLICITUD_INVALIDA", "La solicitud no es válida");
            case 404 -> cuerpo("RECURSO_NO_ENCONTRADO", "El recurso solicitado no existe");
            case 405 -> cuerpo("METODO_NO_PERMITIDO", "Método HTTP no permitido para este recurso");
            case 415 -> cuerpo("TIPO_NO_SOPORTADO", "Tipo de contenido no soportado");
            default -> cuerpo("ERROR_HTTP", "No fue posible procesar la solicitud");
        };
        if (codigo >= 500) {
            log.error("Error HTTP {}", codigo, ex);
        }
        return ResponseEntity.status(status).headers(headers).body(cuerpo);
    }

    private static Map<String, Object> cuerpo(String codigo, String mensaje) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", codigo);
        m.put("mensaje", mensaje);
        return m;
    }
}
