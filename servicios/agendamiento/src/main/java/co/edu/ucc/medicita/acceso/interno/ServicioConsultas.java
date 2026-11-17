package co.edu.ucc.medicita.acceso.interno;

import co.edu.ucc.medicita.agenda.CitaResumen;
import co.edu.ucc.medicita.agenda.ConsultaCitasPaciente;
import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.compartido.UsuarioActual;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Consulta interinstitucional: política de consentimiento + filtro de campos + bitácora de TODA consulta.
 * La auditoría se escribe antes de responder; si falla, no se entrega ningún dato.
 */
@Service
class ServicioConsultas {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ConsultaCitasPaciente citas;
    private final ConsentimientoRepositorio consentimientos;
    private final AuditoriaServicio auditoria;

    ServicioConsultas(ConsultaCitasPaciente citas, ConsentimientoRepositorio consentimientos,
                      AuditoriaServicio auditoria) {
        this.citas = citas;
        this.consentimientos = consentimientos;
        this.auditoria = auditoria;
    }

    List<CitaAutorizada> consultar(UsuarioActual usuario, String documento) {
        if (documento == null || documento.isBlank()) {
            auditoria.registrar(usuario, documento, null, "DENEGADO", "DOCUMENTO_INVALIDO");
            throw ApiException.solicitudInvalida("Indique el documento del paciente");
        }
        String doc = documento.trim();
        Optional<UUID> paciente = citas.pacienteIdPorDocumento(doc);
        if (paciente.isEmpty()) {
            auditoria.registrar(usuario, doc, null, "DENEGADO", "PACIENTE_NO_ENCONTRADO");
            throw ApiException.noEncontrado("PACIENTE_NO_ENCONTRADO", "No existe un paciente con ese documento");
        }
        UUID pacienteId = paciente.get();
        if (usuario.institucion() == null || usuario.institucion().isBlank()) {
            auditoria.registrar(usuario, doc, pacienteId, "DENEGADO", "SIN_INSTITUCION");
            throw new ApiException(HttpStatus.FORBIDDEN, "SIN_CONSENTIMIENTO",
                    "Su usuario no está asociado a una institución aliada");
        }
        if (!consentimientoVigente(pacienteId, usuario.institucion())) {
            auditoria.registrar(usuario, doc, pacienteId, "DENEGADO", "SIN_CONSENTIMIENTO");
            throw new ApiException(HttpStatus.FORBIDDEN, "SIN_CONSENTIMIENTO",
                    "El paciente no ha autorizado a su institución");
        }
        List<CitaAutorizada> visibles = citas.citasVigentesDe(pacienteId).stream().map(ServicioConsultas::filtrar).toList();
        auditoria.registrar(usuario, doc, pacienteId, "PERMITIDO", "CONSENTIMIENTO_VIGENTE");
        return visibles;
    }

    /** Política: consentimiento no revocado ni vencido para el par (paciente, institución). */
    boolean consentimientoVigente(UUID pacienteId, String institucion) {
        return consentimientos.hayVigente(pacienteId, institucion, OffsetDateTime.now());
    }

    /** Filtro de campos: sólo fecha, hora, sede y médico. */
    static CitaAutorizada filtrar(CitaResumen c) {
        OffsetDateTime inicio = Tiempo.bogota(c.inicio());
        return new CitaAutorizada(inicio.toLocalDate().toString(), HORA.format(inicio), c.sede(), c.medico());
    }
}

/** Único contenido visible para una IPS aliada. */
record CitaAutorizada(String fecha, String hora, String sede, String medico) {
}
