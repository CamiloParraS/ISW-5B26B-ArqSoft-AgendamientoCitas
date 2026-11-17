package co.edu.ucc.medicita.acceso.interno;

import co.edu.ucc.medicita.compartido.UsuarioActual;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/acceso")
public class AccesoControlador {

    private final ServicioConsentimientos consentimientos;
    private final ServicioConsultas consultas;

    AccesoControlador(ServicioConsentimientos consentimientos, ServicioConsultas consultas) {
        this.consentimientos = consentimientos;
        this.consultas = consultas;
    }

    @GetMapping("/instituciones")
    @PreAuthorize("hasRole('paciente')")
    public List<InstitucionDto> instituciones() {
        return consentimientos.instituciones();
    }

    @GetMapping("/consentimientos")
    @PreAuthorize("hasRole('paciente')")
    public List<ConsentimientoDto> propios(@AuthenticationPrincipal Jwt jwt) {
        return consentimientos.propios(UsuarioActual.de(jwt).id());
    }

    @PostMapping("/consentimientos")
    @PreAuthorize("hasRole('paciente')")
    public ResponseEntity<ConsentimientoDto> otorgar(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody SolicitudConsentimiento cuerpo) {
        Otorgado r = consentimientos.otorgar(UsuarioActual.de(jwt).id(), cuerpo == null ? null : cuerpo.institucionCodigo(),
                cuerpo == null ? null : cuerpo.vigenteHasta());
        return ResponseEntity.status(r.nuevo() ? HttpStatus.CREATED : HttpStatus.OK).body(r.consentimiento());
    }

    @DeleteMapping("/consentimientos/{id}")
    @PreAuthorize("hasRole('paciente')")
    public ConsentimientoDto revocar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return consentimientos.revocar(UsuarioActual.de(jwt).id(), id);
    }

    @GetMapping("/consultas/citas")
    @PreAuthorize("hasRole('administrativo_ips')")
    public List<CitaAutorizada> consultar(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) String documento) {
        return consultas.consultar(UsuarioActual.de(jwt), documento);
    }

    @GetMapping("/bitacora/mia")
    @PreAuthorize("hasRole('paciente')")
    public List<AccesoDto> bitacora(@AuthenticationPrincipal Jwt jwt) {
        return consentimientos.bitacoraDe(UsuarioActual.de(jwt).id());
    }
}
