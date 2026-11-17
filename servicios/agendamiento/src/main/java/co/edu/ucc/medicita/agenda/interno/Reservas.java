package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.ApiException;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.compartido.UsuarioActual;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import co.edu.ucc.medicita.prestadores.MedicoResumen;
import co.edu.ucc.medicita.prestadores.SedeResumen;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida de la reserva: retención, confirmación, cancelación, reprogramación y atención.
 * Todo contra la base primaria; la caché nunca interviene en estas decisiones.
 */
@Service
class Reservas {

    private static final Logger log = LoggerFactory.getLogger(Reservas.class);

    static final String OP_CONFIRMACION = "CONFIRMACION";
    static final String OP_REPROGRAMACION = "REPROGRAMACION";

    private final JdbcClient jdbc;
    private final Cupos cupos;
    private final Citas citas;
    private final Pacientes pacientes;
    private final Outbox outbox;
    private final CacheCupos cache;
    private final CatalogoPrestadores catalogo;
    private final Politicas politicas;

    Reservas(JdbcClient jdbc, Cupos cupos, Citas citas, Pacientes pacientes, Outbox outbox, CacheCupos cache,
             CatalogoPrestadores catalogo, Politicas politicas) {
        this.jdbc = jdbc;
        this.cupos = cupos;
        this.citas = citas;
        this.pacientes = pacientes;
        this.outbox = outbox;
        this.cache = cache;
        this.catalogo = catalogo;
        this.politicas = politicas;
    }

    // ---------------------------------------------------------------- retención

    /** UPDATE condicional: 1 fila = ganó el cupo; 0 filas = lo perdió (409 con alternativas). */
    @Transactional
    public Retencion retener(UsuarioActual u, UUID cupoId) {
        pacientes.asegurar(u);
        Optional<Object[]> ganado = jdbc.sql("""
                        UPDATE agenda.cupo
                           SET estado = 'RETENIDO', retenido_por = :p, retenido_hasta = now() + interval '5 minutes',
                               version = version + 1
                         WHERE id = :id AND inicio > now()
                           AND (estado = 'LIBRE'
                                OR (estado = 'RETENIDO' AND (retenido_hasta < now() OR retenido_por = :p)))
                        RETURNING retenido_hasta, medico_id, inicio""")
                .param("p", u.id()).param("id", cupoId)
                .query((rs, n) -> new Object[]{rs.getObject("retenido_hasta", OffsetDateTime.class),
                        rs.getObject("medico_id", UUID.class), rs.getObject("inicio", OffsetDateTime.class)})
                .optional();
        if (ganado.isPresent()) {
            Object[] g = ganado.get();
            cache.evictarTrasCommit((UUID) g[1], (OffsetDateTime) g[2]);
            return new Retencion(cupoId, (OffsetDateTime) g[0]);
        }
        CupoRow cupo = cupos.porId(cupoId)
                .orElseThrow(() -> ApiException.noEncontrado("CUPO_NO_ENCONTRADO", "El cupo indicado no existe"));
        throw cupoNoDisponible(cupo, "El cupo ya no está disponible. Estos son los siguientes cupos libres del médico");
    }

    // ------------------------------------------------------------- confirmación

    /** Una sola transacción: cupo + cita + clave de idempotencia + outbox. */
    @Transactional
    public Resultado confirmar(UsuarioActual u, UUID cupoId, String clave) {
        pacientes.asegurar(u);
        citas.bloquearClave(clave);
        Optional<ClaveRow> previa = citas.buscarClave(clave);
        if (previa.isPresent()) {
            return new Resultado(repetir(previa.get(), u, OP_CONFIRMACION), false);
        }
        CupoRow cupo = cupos.porId(cupoId)
                .orElseThrow(() -> ApiException.noEncontrado("CUPO_NO_ENCONTRADO", "El cupo indicado no existe"));
        MedicoResumen medico = medico(cupo);
        SedeResumen sede = sede(cupo);
        reservarCupo(cupo, u.id());

        UUID citaId = UUID.randomUUID();
        citas.insertar(citaId, cupo, u.id());
        citas.insertarClave(clave, u.id(), OP_CONFIRMACION, citaId);
        outbox.registrar("CitaAgendada", "cita.agendada.v1", datosAgendada(citaId, u.id(), cupo, medico, sede));
        cache.evictarTrasCommit(cupo.medicoId(), cupo.inicio());
        log.info("Cita confirmada {} en cupo {}; evento CitaAgendada en la outbox", citaId, cupo.id());
        return new Resultado(new CitaDto(citaId, "CONFIRMADA", cupo.inicio(), cupo.fin(), medico.nombre(),
                medico.especialidad(), sede.nombre()), true);
    }

    // --------------------------------------------------------------- cancelación

    @Transactional
    public Cancelacion cancelar(UsuarioActual u, UUID citaId) {
        pacientes.asegurar(u);
        CitaRow cita = citaDelPaciente(citaId, u);
        if ("CANCELADA".equals(cita.estado())) {
            return new Cancelacion(cita.id(), "CANCELADA", cita.conPenalidad()); // idempotente
        }
        if (!"CONFIRMADA".equals(cita.estado())) {
            throw ApiException.conflicto("CITA_NO_CANCELABLE", "Sólo se pueden cancelar citas confirmadas");
        }
        OffsetDateTime ahora = OffsetDateTime.now();
        if (!cita.inicio().isAfter(ahora)) {
            throw ApiException.conflicto("CITA_PASADA", "La cita ya pasó y no se puede cancelar");
        }
        int horas = politicas.horasMinimas(cita.sedeId()); // política vigente en este instante
        boolean conPenalidad = ahora.isAfter(cita.inicio().minusHours(horas));

        anularCita(cita, conPenalidad);
        liberarCupo(cita.cupoId());
        outbox.registrar("CitaCancelada", "cita.cancelada.v1", datosCancelada(cita, conPenalidad, "PACIENTE"));
        cache.evictarTrasCommit(cita.medicoId(), cita.inicio());
        return new Cancelacion(cita.id(), "CANCELADA", conPenalidad);
    }

    // ------------------------------------------------------------ reprogramación

    @Transactional
    public Resultado reprogramar(UsuarioActual u, UUID citaId, UUID nuevoCupoId, String clave) {
        pacientes.asegurar(u);
        citas.bloquearClave(clave);
        Optional<ClaveRow> previa = citas.buscarClave(clave);
        if (previa.isPresent()) {
            return new Resultado(repetir(previa.get(), u, OP_REPROGRAMACION), false);
        }
        CitaRow vieja = citaDelPaciente(citaId, u);
        if (!"CONFIRMADA".equals(vieja.estado())) {
            throw ApiException.conflicto("CITA_NO_REPROGRAMABLE", "Sólo se pueden reprogramar citas confirmadas");
        }
        if (!vieja.inicio().isAfter(OffsetDateTime.now())) {
            throw ApiException.conflicto("CITA_PASADA", "La cita ya pasó y no se puede reprogramar");
        }
        if (vieja.cupoId().equals(nuevoCupoId)) {
            throw ApiException.solicitudInvalida("El nuevo cupo debe ser distinto al de la cita actual");
        }
        CupoRow nuevo = cupos.porId(nuevoCupoId)
                .orElseThrow(() -> ApiException.noEncontrado("CUPO_NO_ENCONTRADO", "El cupo indicado no existe"));
        MedicoResumen medico = medico(nuevo);
        SedeResumen sede = sede(nuevo);
        reservarCupo(nuevo, u.id());

        anularCita(vieja, false);
        liberarCupo(vieja.cupoId());
        UUID nuevaId = UUID.randomUUID();
        citas.insertar(nuevaId, nuevo, u.id());
        citas.insertarClave(clave, u.id(), OP_REPROGRAMACION, nuevaId);
        outbox.registrar("CitaCancelada", "cita.cancelada.v1", datosCancelada(vieja, false, "REPROGRAMACION"));
        outbox.registrar("CitaAgendada", "cita.agendada.v1", datosAgendada(nuevaId, u.id(), nuevo, medico, sede));
        cache.evictarTrasCommit(vieja.medicoId(), vieja.inicio());
        cache.evictarTrasCommit(nuevo.medicoId(), nuevo.inicio());
        return new Resultado(new CitaDto(nuevaId, "CONFIRMADA", nuevo.inicio(), nuevo.fin(), medico.nombre(),
                medico.especialidad(), sede.nombre()), true);
    }

    // ------------------------------------------------------------------ atención

    @Transactional
    public CitaDto atender(UsuarioActual medicoActual, UUID citaId) {
        CitaRow cita = citas.bloquear(citaId).filter(c -> c.medicoId().equals(medicoActual.id()))
                .orElseThrow(() -> ApiException.noEncontrado("CITA_NO_ENCONTRADA", "La cita indicada no existe en su agenda"));
        if ("ATENDIDA".equals(cita.estado())) {
            return dto(cita); // idempotente
        }
        if (!"CONFIRMADA".equals(cita.estado())) {
            throw ApiException.conflicto("CITA_NO_ATENDIBLE", "Sólo se pueden atender citas confirmadas");
        }
        OffsetDateTime ahora = Tiempo.ahora();
        jdbc.sql("UPDATE agenda.cita SET estado = 'ATENDIDA', atendida_en = :a WHERE id = :id")
                .param("a", ahora).param("id", cita.id()).update();
        MedicoResumen medico = catalogo.medico(cita.medicoId()).orElse(null);
        SedeResumen sede = catalogo.sede(cita.sedeId()).orElse(null);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("citaId", cita.id().toString());
        d.put("pacienteId", cita.pacienteId().toString());
        d.put("medicoId", cita.medicoId().toString());
        d.put("medicoNombre", medico == null ? null : medico.nombre());
        d.put("especialidad", medico == null ? null : medico.especialidad());
        d.put("sedeNombre", sede == null ? null : sede.nombre());
        d.put("inicio", Tiempo.iso(cita.inicio()));
        d.put("fin", Tiempo.iso(cita.fin()));
        d.put("atendidaEn", Tiempo.iso(ahora));
        outbox.registrar("CitaAtendida", "cita.atendida.v1", d);
        return new CitaDto(cita.id(), "ATENDIDA", cita.inicio(), cita.fin(),
                medico == null ? null : medico.nombre(), medico == null ? null : medico.especialidad(),
                sede == null ? null : sede.nombre());
    }

    // --------------------------------------------------------------------- mis citas

    @Transactional(readOnly = true)
    public List<CitaMiaDto> mias(UsuarioActual u) {
        Map<UUID, MedicoResumen> medicos = new HashMap<>();
        Map<UUID, SedeResumen> sedes = new HashMap<>();
        Map<UUID, Integer> horas = new HashMap<>();
        OffsetDateTime ahora = OffsetDateTime.now();
        return citas.delPaciente(u.id()).stream().map(c -> {
            MedicoResumen m = medicos.computeIfAbsent(c.medicoId(), id -> catalogo.medico(id).orElse(null));
            SedeResumen s = sedes.computeIfAbsent(c.sedeId(), id -> catalogo.sede(id).orElse(null));
            int h = horas.computeIfAbsent(c.sedeId(), politicas::horasMinimas);
            return new CitaMiaDto(c.id(), c.estado(), c.inicio(), c.fin(), c.medicoId(),
                    m == null ? null : m.nombre(), m == null ? null : m.especialidad(), c.sedeId(),
                    s == null ? null : s.nombre(), c.conPenalidad(), h, ahora.isAfter(c.inicio().minusHours(h)));
        }).toList();
    }

    // ------------------------------------------------------------------ utilidades

    /** Reintento con la misma clave: devuelve la misma cita si es del mismo paciente y operación. */
    private CitaDto repetir(ClaveRow clave, UsuarioActual u, String operacion) {
        if (!clave.pacienteId().equals(u.id()) || !operacion.equals(clave.operacion())) {
            throw ApiException.conflicto("CLAVE_IDEMPOTENCIA_EN_USO",
                    "La clave de idempotencia ya fue usada en otra solicitud");
        }
        return citas.porId(clave.citaId()).map(this::dto)
                .orElseThrow(() -> new IllegalStateException("Clave sin cita: " + clave.clave()));
    }

    private void reservarCupo(CupoRow cupo, UUID pacienteId) {
        int n = jdbc.sql("""
                        UPDATE agenda.cupo SET estado = 'RESERVADO', version = version + 1
                         WHERE id = :id AND estado = 'RETENIDO' AND retenido_por = :p AND retenido_hasta >= now()""")
                .param("id", cupo.id()).param("p", pacienteId).update();
        if (n == 0) {
            throw cupoNoDisponible(cupo, "La retención del cupo venció o se perdió. Estos son los siguientes cupos libres");
        }
    }

    private void liberarCupo(UUID cupoId) {
        jdbc.sql("""
                        UPDATE agenda.cupo SET estado = 'LIBRE', retenido_por = NULL, retenido_hasta = NULL,
                               version = version + 1
                         WHERE id = :id""").param("id", cupoId).update();
    }

    private void anularCita(CitaRow cita, boolean conPenalidad) {
        jdbc.sql("UPDATE agenda.cita SET estado = 'CANCELADA', cancelada_en = now(), con_penalidad = :c WHERE id = :id")
                .param("c", conPenalidad).param("id", cita.id()).update();
    }

    private CitaRow citaDelPaciente(UUID citaId, UsuarioActual u) {
        return citas.bloquear(citaId).filter(c -> c.pacienteId().equals(u.id()))
                .orElseThrow(() -> ApiException.noEncontrado("CITA_NO_ENCONTRADA", "La cita indicada no existe"));
    }

    private MedicoResumen medico(CupoRow cupo) {
        return catalogo.medico(cupo.medicoId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "MEDICO_NO_DISPONIBLE", "El médico de este cupo ya no está disponible"));
    }

    private SedeResumen sede(CupoRow cupo) {
        return catalogo.sede(cupo.sedeId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "SEDE_NO_DISPONIBLE", "La sede de este cupo ya no está disponible"));
    }

    private ApiException cupoNoDisponible(CupoRow cupo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, "CUPO_NO_DISPONIBLE", mensaje)
                .con("alternativas", cupos.alternativasDto(cupo));
    }

    private CitaDto dto(CitaRow c) {
        MedicoResumen m = catalogo.medico(c.medicoId()).orElse(null);
        SedeResumen s = catalogo.sede(c.sedeId()).orElse(null);
        return new CitaDto(c.id(), c.estado(), c.inicio(), c.fin(), m == null ? null : m.nombre(),
                m == null ? null : m.especialidad(), s == null ? null : s.nombre());
    }

    private static Map<String, Object> datosAgendada(UUID citaId, UUID pacienteId, CupoRow cupo, MedicoResumen medico,
                                                     SedeResumen sede) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("citaId", citaId.toString());
        d.put("pacienteId", pacienteId.toString());
        d.put("medicoId", cupo.medicoId().toString());
        d.put("medicoNombre", medico.nombre());
        d.put("especialidad", medico.especialidad());
        d.put("sedeId", sede.id().toString());
        d.put("sedeNombre", sede.nombre());
        d.put("inicio", Tiempo.iso(cupo.inicio()));
        d.put("fin", Tiempo.iso(cupo.fin()));
        return d;
    }

    private Map<String, Object> datosCancelada(CitaRow cita, boolean conPenalidad, String motivo) {
        MedicoResumen medico = catalogo.medico(cita.medicoId()).orElse(null);
        SedeResumen sede = catalogo.sede(cita.sedeId()).orElse(null);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("citaId", cita.id().toString());
        d.put("pacienteId", cita.pacienteId().toString());
        d.put("medicoId", cita.medicoId().toString());
        d.put("medicoNombre", medico == null ? null : medico.nombre());
        d.put("sedeNombre", sede == null ? null : sede.nombre());
        d.put("inicio", Tiempo.iso(cita.inicio()));
        d.put("conPenalidad", conPenalidad);
        d.put("motivo", motivo);
        return d;
    }
}
