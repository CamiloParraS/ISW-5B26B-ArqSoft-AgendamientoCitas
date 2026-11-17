package co.edu.ucc.medicita.integracionclinica.hce;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import org.slf4j.MDC;

/** Propaga el MDC correlacionId como cabecera X-Correlation-Id en cada llamada FHIR. */
public class CorrelacionInterceptor implements IClientInterceptor {

    public static final String CABECERA = "X-Correlation-Id";
    public static final String MDC_CLAVE = "correlacionId";

    @Override
    public void interceptRequest(IHttpRequest request) {
        String correlacion = MDC.get(MDC_CLAVE);
        if (correlacion != null && !correlacion.isBlank()) {
            request.addHeader(CABECERA, correlacion);
        }
    }

    @Override
    public void interceptResponse(IHttpResponse response) {
        // sin acción
    }
}
