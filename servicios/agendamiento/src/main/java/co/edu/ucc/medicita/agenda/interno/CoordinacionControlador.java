package co.edu.ucc.medicita.agenda.interno;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operaciones del coordinador: políticas de cancelación, reporte de ocupación (réplica) y estado de la outbox. */
@RestController
@RequestMapping("/api/agenda")
@PreAuthorize("hasRole('coordinador')")
public class CoordinacionControlador {

    private final Politicas politicas;
    private final ServicioReporteOcupacion reporte;
    private final Outbox outbox;

    CoordinacionControlador(Politicas politicas, ServicioReporteOcupacion reporte, Outbox outbox) {
        this.politicas = politicas;
        this.reporte = reporte;
        this.outbox = outbox;
    }

    @GetMapping("/politicas-cancelacion")
    public List<PoliticaDto> politicas() {
        return politicas.listar();
    }

    @PutMapping("/politicas-cancelacion/{sedeId}")
    public PoliticaDto actualizar(@PathVariable UUID sedeId, @RequestBody SolicitudPolitica cuerpo) {
        return politicas.actualizar(sedeId, cuerpo == null ? null : cuerpo.horasMinimas());
    }

    @GetMapping("/reportes/ocupacion")
    public ReporteOcupacion ocupacion(@RequestParam(required = false) String mes) {
        return reporte.generar(mes);
    }

    @GetMapping("/operacion/outbox")
    public EstadoOutbox estadoOutbox() {
        return outbox.estado();
    }
}
