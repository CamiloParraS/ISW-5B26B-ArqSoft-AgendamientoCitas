package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import co.edu.ucc.medicita.prestadores.MedicoResumen;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Búsqueda de cupos libres: lee de Redis (X-Cache HIT|MISS) y cae a la base de datos. */
@RestController
@RequestMapping("/api/agenda/cupos")
public class CuposControlador {

    private static final int HORIZONTE_DIAS = 30;

    private final Cupos cupos;
    private final CacheCupos cache;
    private final CatalogoPrestadores catalogo;

    CuposControlador(Cupos cupos, CacheCupos cache, CatalogoPrestadores catalogo) {
        this.cupos = cupos;
        this.cache = cache;
        this.catalogo = catalogo;
    }

    @GetMapping
    @PreAuthorize("hasRole('paciente')")
    public ResponseEntity<List<CupoDto>> buscar(@RequestParam(required = false) UUID especialidadId,
                                                @RequestParam(required = false) UUID medicoId,
                                                @RequestParam(required = false) String fecha) {
        LocalDate dia = parsear(fecha);
        List<MedicoResumen> medicos = medicos(especialidadId, medicoId);
        boolean todoDeCache = true;
        OffsetDateTime ahora = OffsetDateTime.now();
        List<CupoDto> resultado = new ArrayList<>();
        for (MedicoResumen m : medicos) {
            var enCache = cache.obtener(m.id(), dia);
            List<CupoDto> lista;
            if (enCache.isPresent()) {
                lista = enCache.get();
            } else {
                todoDeCache = false;
                lista = cupos.aDtos(cupos.libresDelDia(m.id(), dia));
                cache.guardar(m.id(), dia, lista);
            }
            lista.stream().filter(c -> c.inicio().isAfter(ahora)).forEach(resultado::add);
        }
        resultado.sort(Comparator.comparing(CupoDto::inicio).thenComparing(CupoDto::medicoNombre,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return ResponseEntity.ok().header("X-Cache", todoDeCache && !medicos.isEmpty() ? "HIT" : "MISS").body(resultado);
    }

    /** Primeros 10 cupos libres desde ahora ("primer cupo disponible"). */
    @GetMapping("/proximos")
    @PreAuthorize("hasRole('paciente')")
    public ResponseEntity<List<CupoDto>> proximos(@RequestParam UUID especialidadId) {
        List<MedicoResumen> medicos = catalogo.medicosPorEspecialidad(especialidadId);
        OffsetDateTime ahora = OffsetDateTime.now();
        LocalDate dia = Tiempo.hoy();
        boolean todoDeCache = !medicos.isEmpty();
        List<CupoDto> resultado = new ArrayList<>();
        // Lee las mismas entradas cupos-libres:{medico}:{fecha} que la búsqueda, día a día, hasta juntar 10
        for (int d = 0; d < HORIZONTE_DIAS && resultado.size() < 10 && !medicos.isEmpty(); d++, dia = dia.plusDays(1)) {
            List<CupoDto> delDia = new ArrayList<>();
            for (MedicoResumen m : medicos) {
                var enCache = cache.obtener(m.id(), dia);
                List<CupoDto> lista;
                if (enCache.isPresent()) {
                    lista = enCache.get();
                } else {
                    todoDeCache = false;
                    lista = cupos.aDtos(cupos.libresDelDia(m.id(), dia));
                    cache.guardar(m.id(), dia, lista);
                }
                lista.stream().filter(c -> c.inicio().isAfter(ahora)).forEach(delDia::add);
            }
            delDia.sort(Comparator.comparing(CupoDto::inicio).thenComparing(CupoDto::medicoNombre,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            resultado.addAll(delDia);
        }
        List<CupoDto> primeros = resultado.size() > 10 ? new ArrayList<>(resultado.subList(0, 10)) : resultado;
        return ResponseEntity.ok().header("X-Cache", todoDeCache ? "HIT" : "MISS").body(primeros);
    }

    private List<MedicoResumen> medicos(UUID especialidadId, UUID medicoId) {
        if (medicoId != null) {
            return List.of(catalogo.medico(medicoId)
                    .orElseThrow(() -> ApiException.noEncontrado("MEDICO_NO_ENCONTRADO", "El médico indicado no existe")));
        }
        if (especialidadId != null) {
            return catalogo.medicosPorEspecialidad(especialidadId);
        }
        throw ApiException.solicitudInvalida("Indique especialidadId o medicoId");
    }

    static LocalDate parsear(String fecha) {
        if (fecha == null || fecha.isBlank()) {
            return Tiempo.hoy();
        }
        try {
            return LocalDate.parse(fecha);
        } catch (DateTimeParseException e) {
            throw ApiException.solicitudInvalida("La fecha debe tener el formato YYYY-MM-DD");
        }
    }
}
