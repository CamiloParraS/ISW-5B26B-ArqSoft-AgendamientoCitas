package co.edu.ucc.medicita.acceso.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consentimientos del paciente hacia instituciones aliadas y su bitácora de accesos. */
@Service
class ServicioConsentimientos {

    private final InstitucionRepositorio instituciones;
    private final ConsentimientoRepositorio consentimientos;
    private final BitacoraRepositorio bitacora;

    ServicioConsentimientos(InstitucionRepositorio instituciones, ConsentimientoRepositorio consentimientos,
                            BitacoraRepositorio bitacora) {
        this.instituciones = instituciones;
        this.consentimientos = consentimientos;
        this.bitacora = bitacora;
    }

    @Transactional(readOnly = true)
    public List<InstitucionDto> instituciones() {
        return instituciones.findAll().stream().map(i -> new InstitucionDto(i.getCodigo(), i.getNombre()))
                .sorted(Comparator.comparing(InstitucionDto::nombre)).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsentimientoDto> propios(UUID pacienteId) {
        Map<String, String> nombres = nombres();
        OffsetDateTime ahora = OffsetDateTime.now();
        return consentimientos.findByPacienteIdOrderByOtorgadoEnDesc(pacienteId).stream()
                .map(c -> dto(c, nombres, ahora)).toList();
    }

    /** Crea el consentimiento (201) o, si ya hay uno vigente para la institución, extiende su vigencia (200). */
    @Transactional
    public Otorgado otorgar(UUID pacienteId, String institucionCodigo, OffsetDateTime vigenteHasta) {
        if (institucionCodigo == null || institucionCodigo.isBlank()) {
            throw ApiException.solicitudInvalida("Falta el campo institucionCodigo");
        }
        if (!instituciones.existsById(institucionCodigo)) {
            throw ApiException.noEncontrado("INSTITUCION_NO_ENCONTRADA", "La institución indicada no existe");
        }
        OffsetDateTime ahora = OffsetDateTime.now();
        OffsetDateTime hasta = vigenteHasta != null ? vigenteHasta : ahora.plusYears(1);
        if (!hasta.isAfter(ahora)) {
            throw ApiException.solicitudInvalida("vigenteHasta debe ser una fecha futura");
        }
        Map<String, String> nombres = nombres();
        var vigentes = consentimientos.vigentes(pacienteId, institucionCodigo, ahora);
        if (!vigentes.isEmpty()) {
            Consentimiento existente = vigentes.get(0);
            existente.extenderHasta(hasta);
            return new Otorgado(dto(consentimientos.save(existente), nombres, ahora), false);
        }
        Consentimiento nuevo = consentimientos.save(new Consentimiento(UUID.randomUUID(), pacienteId, institucionCodigo, ahora, hasta));
        return new Otorgado(dto(nuevo, nombres, ahora), true);
    }

    @Transactional
    public ConsentimientoDto revocar(UUID pacienteId, UUID id) {
        Consentimiento c = consentimientos.findByIdAndPacienteId(id, pacienteId)
                .orElseThrow(() -> ApiException.noEncontrado("CONSENTIMIENTO_NO_ENCONTRADO", "El consentimiento indicado no existe"));
        c.revocar(OffsetDateTime.now());
        return dto(consentimientos.save(c), nombres(), OffsetDateTime.now());
    }

    /** "Quién consultó mis datos": accesos registrados sobre el paciente (Ley 1581, trazabilidad). */
    @Transactional(readOnly = true)
    public List<AccesoDto> bitacoraDe(UUID pacienteId) {
        Map<String, String> nombres = nombres();
        return bitacora.findTop200ByPacienteIdOrderByOcurridoEnDesc(pacienteId).stream()
                .map(b -> new AccesoDto(b.getId(), Tiempo.bogota(b.getOcurridoEn()), b.getUsuarioNombre(),
                        b.getInstitucionCodigo(), nombres.get(b.getInstitucionCodigo()), b.getResultado(),
                        b.getMotivo())).toList();
    }

    private Map<String, String> nombres() {
        return instituciones.findAll().stream().collect(Collectors.toMap(Institucion::getCodigo, Institucion::getNombre));
    }

    private static ConsentimientoDto dto(Consentimiento c, Map<String, String> nombres, OffsetDateTime ahora) {
        return new ConsentimientoDto(c.getId(), c.getInstitucionCodigo(), nombres.get(c.getInstitucionCodigo()),
                Tiempo.bogota(c.getOtorgadoEn()), Tiempo.bogota(c.getVigenteHasta()), Tiempo.bogota(c.getRevocadoEn()),
                c.vigente(ahora));
    }
}

record InstitucionDto(String codigo, String nombre) {
}

record ConsentimientoDto(UUID id, String institucionCodigo, String institucionNombre, OffsetDateTime otorgadoEn,
                         OffsetDateTime vigenteHasta, OffsetDateTime revocadoEn, boolean vigente) {
}

record SolicitudConsentimiento(String institucionCodigo, OffsetDateTime vigenteHasta) {
}

record Otorgado(ConsentimientoDto consentimiento, boolean nuevo) {
}

record AccesoDto(Long id, OffsetDateTime ocurridoEn, String usuarioNombre, String institucionCodigo,
                 String institucionNombre, String resultado, String motivo) {
}
