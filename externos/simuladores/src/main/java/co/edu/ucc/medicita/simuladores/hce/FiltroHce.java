package co.edu.ucc.medicita.simuladores.hce;

import co.edu.ucc.medicita.simuladores.control.EstadoSimuladores;
import co.edu.ucc.medicita.simuladores.registro.Registro;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filtro de /fhir/*: simula la caída (503) y registra cada llamada con su X-Correlation-Id. */
public class FiltroHce extends OncePerRequestFilter {

    private final EstadoSimuladores estado;
    private final Registro registro;

    public FiltroHce(EstadoSimuladores estado, Registro registro) {
        this.estado = estado;
        this.registro = registro;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String correlacion = peticion.getHeader("X-Correlation-Id");
        if (correlacion != null) {
            respuesta.setHeader("X-Correlation-Id", correlacion);
        }
        if (estado.hceCaida()) {
            respuesta.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            respuesta.setContentType("application/fhir+json;charset=UTF-8");
            respuesta.getOutputStream().write(("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\","
                    + "\"code\":\"transient\",\"diagnostics\":\"HCE no disponible (caída simulada)\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
            registrar(peticion, correlacion, 503, "HCE caída (simulada)");
            return;
        }
        cadena.doFilter(peticion, respuesta);
        Object resumen = peticion.getAttribute(Anotaciones.ATRIBUTO);
        registrar(peticion, correlacion, respuesta.getStatus(), resumen == null ? null : resumen.toString());
    }

    private void registrar(HttpServletRequest peticion, String correlacion, int estadoHttp, String recurso) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("metodo", peticion.getMethod());
        String consulta = peticion.getQueryString();
        datos.put("ruta", peticion.getRequestURI() + (consulta == null ? "" : "?" + consulta));
        datos.put("estado", estadoHttp);
        datos.put("recurso", recurso);
        registro.hce(correlacion, datos);
    }
}
