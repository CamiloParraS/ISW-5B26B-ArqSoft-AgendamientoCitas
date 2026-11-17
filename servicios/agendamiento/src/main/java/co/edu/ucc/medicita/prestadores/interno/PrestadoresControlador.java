package co.edu.ucc.medicita.prestadores.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.MedicoResumen;
import co.edu.ucc.medicita.prestadores.SedeResumen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prestadores")
public class PrestadoresControlador {

    private final SedeRepositorio sedes;
    private final EspecialidadRepositorio especialidades;
    private final MedicoRepositorio medicos;
    private final PublicacionHorarios publicacion;

    PrestadoresControlador(SedeRepositorio sedes, EspecialidadRepositorio especialidades, MedicoRepositorio medicos,
                           PublicacionHorarios publicacion) {
        this.sedes = sedes;
        this.especialidades = especialidades;
        this.medicos = medicos;
        this.publicacion = publicacion;
    }

    @GetMapping("/sedes")
    @Transactional(readOnly = true)
    public List<SedeResumen> sedes() {
        return sedes.findAll().stream().map(s -> new SedeResumen(s.getId(), s.getNombre(), s.getDireccion()))
                .sorted(java.util.Comparator.comparing(SedeResumen::nombre)).toList();
    }

    @GetMapping("/especialidades")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> especialidades() {
        return especialidades.findAll().stream()
                .sorted(java.util.Comparator.comparing(Especialidad::getNombre))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("nombre", e.getNombre());
                    return m;
                }).toList();
    }

    @GetMapping("/medicos")
    @Transactional(readOnly = true)
    public List<MedicoResumen> medicos(@RequestParam(required = false) UUID especialidadId) {
        return especialidadId == null ? medicos.todos() : medicos.porEspecialidad(especialidadId);
    }

    @PostMapping("/horarios")
    @PreAuthorize("hasRole('coordinador')")
    public ResponseEntity<Map<String, Object>> publicar(@RequestBody SolicitudHorario solicitud) {
        Horario h = publicacion.publicar(solicitud);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("id", h.getId());
        cuerpo.put("medicoId", h.getMedicoId());
        cuerpo.put("sedeId", h.getSedeId());
        cuerpo.put("desde", h.getDesde());
        cuerpo.put("hasta", h.getHasta());
        cuerpo.put("diasSemana", h.getDiasSemana());
        cuerpo.put("horaInicio", h.getHoraInicio());
        cuerpo.put("horaFin", h.getHoraFin());
        cuerpo.put("duracionMinutos", h.getDuracionMinutos());
        cuerpo.put("publicadoEn", Tiempo.bogota(h.getPublicadoEn()));
        return ResponseEntity.status(HttpStatus.CREATED).body(cuerpo);
    }
}
