package co.edu.ucc.medicita.notificaciones.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Lee o genera X-Correlation-Id, lo pone en el MDC (correlacionId) y lo devuelve en la respuesta. */
@Component
public class FiltroCorrelacion extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Correlation-Id");
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        MDC.put("correlacionId", id);
        res.setHeader("X-Correlation-Id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("correlacionId");
        }
    }
}
