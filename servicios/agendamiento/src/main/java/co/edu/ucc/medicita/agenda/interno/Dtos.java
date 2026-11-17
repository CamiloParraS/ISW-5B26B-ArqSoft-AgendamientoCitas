package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.compartido.Tiempo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Cupo libre tal como lo ve el paciente (también es el valor guardado en Redis). */
record CupoDto(UUID id, UUID medicoId, String medicoNombre, String especialidad, UUID sedeId, String sedeNombre,
               OffsetDateTime inicio, OffsetDateTime fin) {
    CupoDto {
        inicio = Tiempo.bogota(inicio);
        fin = Tiempo.bogota(fin);
    }
}

/** Respuesta de confirmación / reprogramación. */
record CitaDto(UUID id, String estado, OffsetDateTime inicio, OffsetDateTime fin, String medicoNombre,
               String especialidad, String sedeNombre) {
    CitaDto {
        inicio = Tiempo.bogota(inicio);
        fin = Tiempo.bogota(fin);
    }
}

/** Cita en "mis citas": añade datos para que la app muestre la política de cancelación vigente. */
record CitaMiaDto(UUID id, String estado, OffsetDateTime inicio, OffsetDateTime fin, UUID medicoId,
                  String medicoNombre, String especialidad, UUID sedeId, String sedeNombre, boolean conPenalidad,
                  int horasMinimasCancelacion, boolean cancelacionConPenalidad) {
    CitaMiaDto {
        inicio = Tiempo.bogota(inicio);
        fin = Tiempo.bogota(fin);
    }
}

record SolicitudCupo(UUID cupoId) {
}

record Retencion(UUID cupoId, OffsetDateTime retenidoHasta) {
    Retencion {
        retenidoHasta = Tiempo.bogota(retenidoHasta);
    }
}

record Cancelacion(UUID id, String estado, boolean conPenalidad) {
}

record Resultado(CitaDto cita, boolean nueva) {
}

record CupoMedicoDto(UUID id, OffsetDateTime inicio, OffsetDateTime fin, String estado, UUID sedeId, UUID citaId,
                     String citaEstado, String pacienteNombre) {
    CupoMedicoDto {
        inicio = Tiempo.bogota(inicio);
        fin = Tiempo.bogota(fin);
    }
}

record PoliticaDto(UUID sedeId, String sedeNombre, int horasMinimas, OffsetDateTime actualizadaEn) {
    PoliticaDto {
        actualizadaEn = Tiempo.bogota(actualizadaEn);
    }
}

record SolicitudPolitica(Integer horasMinimas) {
}

record OcupacionMedico(UUID medicoId, String medicoNombre, long totalCupos, long reservados, long atendidos,
                       double porcentaje) {
}

record ReporteOcupacion(String mes, List<OcupacionMedico> medicos) {
}

record EstadoOutbox(long pendientes, long antiguedadMasAntiguaSegundos, boolean alarma) {
}
