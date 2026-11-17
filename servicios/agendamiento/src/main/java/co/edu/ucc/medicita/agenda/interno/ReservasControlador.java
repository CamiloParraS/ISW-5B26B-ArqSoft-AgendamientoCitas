package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.UsuarioActual;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agenda")
@PreAuthorize("hasRole('paciente')")
public class ReservasControlador {

    private final Reservas reservas;

    ReservasControlador(Reservas reservas) {
        this.reservas = reservas;
    }

    @PostMapping("/retenciones")
    public ResponseEntity<Retencion> retener(@AuthenticationPrincipal Jwt jwt, @RequestBody SolicitudCupo cuerpo) {
        UUID cupoId = cupo(cuerpo);
        return ResponseEntity.status(HttpStatus.CREATED).body(reservas.retener(UsuarioActual.de(jwt), cupoId));
    }

    @PostMapping("/citas")
    public ResponseEntity<CitaDto> confirmar(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String clave,
                                             @RequestBody SolicitudCupo cuerpo) {
        String k = clave(clave);
        Resultado r = reservas.confirmar(UsuarioActual.de(jwt), cupo(cuerpo), k);
        return ResponseEntity.status(r.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(r.cita());
    }

    @GetMapping("/citas/mias")
    public List<CitaMiaDto> mias(@AuthenticationPrincipal Jwt jwt) {
        return reservas.mias(UsuarioActual.de(jwt));
    }

    @PostMapping("/citas/{id}/cancelacion")
    public Cancelacion cancelar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return reservas.cancelar(UsuarioActual.de(jwt), id);
    }

    @PostMapping("/citas/{id}/reprogramacion")
    public ResponseEntity<CitaDto> reprogramar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String clave,
                                               @RequestBody SolicitudCupo cuerpo) {
        String k = clave(clave);
        Resultado r = reservas.reprogramar(UsuarioActual.de(jwt), id, cupo(cuerpo), k);
        return ResponseEntity.status(r.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(r.cita());
    }

    private static UUID cupo(SolicitudCupo cuerpo) {
        if (cuerpo == null || cuerpo.cupoId() == null) {
            throw ApiException.solicitudInvalida("Falta el campo cupoId");
        }
        return cuerpo.cupoId();
    }

    private static String clave(String valor) {
        if (valor == null || valor.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLAVE_IDEMPOTENCIA_REQUERIDA",
                    "Falta el encabezado Idempotency-Key");
        }
        if (valor.length() > 200) {
            throw ApiException.solicitudInvalida("Idempotency-Key supera los 200 caracteres");
        }
        return valor.trim();
    }
}
