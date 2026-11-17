package co.edu.ucc.medicita.prestadores.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.prestadores.HorarioPublicado;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publica un horario y avisa a Agenda con el evento HorarioPublicado (en proceso). */
@Service
class PublicacionHorarios {

    private static final List<Integer> LUNES_A_VIERNES = List.of(1, 2, 3, 4, 5);

    private final MedicoRepositorio medicos;
    private final HorarioRepositorio horarios;
    private final ApplicationEventPublisher eventos;

    PublicacionHorarios(MedicoRepositorio medicos, HorarioRepositorio horarios, ApplicationEventPublisher eventos) {
        this.medicos = medicos;
        this.horarios = horarios;
        this.eventos = eventos;
    }

    @Transactional
    public Horario publicar(SolicitudHorario s) {
        if (s == null || s.medicoId() == null || s.desde() == null || s.hasta() == null || s.horaInicio() == null
                || s.horaFin() == null || s.duracionMinutos() == null) {
            throw ApiException.solicitudInvalida("Faltan datos: medicoId, desde, hasta, horaInicio, horaFin, duracionMinutos");
        }
        if (s.hasta().isBefore(s.desde()) || ChronoUnit.DAYS.between(s.desde(), s.hasta()) > 366) {
            throw ApiException.solicitudInvalida("El rango de fechas no es válido (máximo 366 días)");
        }
        if (!s.horaFin().isAfter(s.horaInicio()) || s.duracionMinutos() < 5 || s.duracionMinutos() > 240) {
            throw ApiException.solicitudInvalida("El rango de horas o la duración del cupo no son válidos");
        }
        List<Integer> dias = s.diasSemana() == null || s.diasSemana().isEmpty() ? LUNES_A_VIERNES : s.diasSemana();
        if (dias.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
            throw ApiException.solicitudInvalida("diasSemana debe contener días ISO entre 1 (lunes) y 7 (domingo)");
        }
        List<Integer> diasOrdenados = dias.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        Medico medico = medicos.findById(s.medicoId())
                .orElseThrow(() -> ApiException.noEncontrado("MEDICO_NO_ENCONTRADO", "El médico indicado no existe"));

        Horario h = new Horario(UUID.randomUUID(), medico.getId(), medico.getSedeId(), s.desde(), s.hasta(),
                diasOrdenados.stream().map(String::valueOf).collect(Collectors.joining(",")),
                s.horaInicio(), s.horaFin(), s.duracionMinutos(), Tiempo.ahora());
        horarios.save(h);
        eventos.publishEvent(new HorarioPublicado(h.getId(), h.getMedicoId(), h.getSedeId(), h.getDesde(), h.getHasta(),
                diasOrdenados, h.getHoraInicio(), h.getHoraFin(), h.getDuracionMinutos()));
        return h;
    }
}

record SolicitudHorario(UUID medicoId, LocalDate desde, LocalDate hasta, List<Integer> diasSemana,
                        LocalTime horaInicio, LocalTime horaFin, Integer duracionMinutos) {
}
