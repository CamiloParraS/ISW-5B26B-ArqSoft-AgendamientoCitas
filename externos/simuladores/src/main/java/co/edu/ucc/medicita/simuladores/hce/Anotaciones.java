package co.edu.ucc.medicita.simuladores.hce;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

/** Deja un resumen del recurso tocado en la petición para que el filtro lo registre. */
final class Anotaciones {

    static final String ATRIBUTO = "hce.recurso";

    private Anotaciones() {
    }

    static void anotar(RequestDetails detalles, String resumen) {
        if (detalles instanceof ServletRequestDetails servlet && servlet.getServletRequest() != null) {
            servlet.getServletRequest().setAttribute(ATRIBUTO, resumen);
        }
    }
}
