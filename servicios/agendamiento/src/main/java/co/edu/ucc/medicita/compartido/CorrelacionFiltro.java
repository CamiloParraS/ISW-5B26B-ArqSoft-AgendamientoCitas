package co.edu.ucc.medicita.compartido;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Lee o genera X-Correlation-Id, lo pone en MDC (correlacionId), lo devuelve y marca la instancia (X-Instancia). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelacionFiltro extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";
    public static final String ENCABEZADO_INSTANCIA = "X-Instancia";
    public static final String MDC_CLAVE = "correlacionId";
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final String instancia;

    public CorrelacionFiltro(@Value("${medicita.instancia}") String instancia) {
        this.instancia = instancia;
    }

    /** Correlación de la petición en curso (o una nueva si se invoca fuera de HTTP). */
    public static String actual() {
        String id = MDC.get(MDC_CLAVE);
        return id != null ? id : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(ENCABEZADO);
        if (id == null || !VALIDO.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_CLAVE, id);
        response.setHeader(ENCABEZADO, id);
        response.setHeader(ENCABEZADO_INSTANCIA, instancia);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CLAVE);
        }
    }
}
