package co.edu.ucc.medicita.prestadores.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "horario", schema = "prestadores")
public class Horario {

    @Id
    private UUID id;
    @Column(name = "medico_id", nullable = false)
    private UUID medicoId;
    @Column(name = "sede_id", nullable = false)
    private UUID sedeId;
    @Column(nullable = false)
    private LocalDate desde;
    @Column(nullable = false)
    private LocalDate hasta;
    @Column(name = "dias_semana", nullable = false)
    private String diasSemana;
    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;
    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;
    @Column(name = "duracion_minutos", nullable = false)
    private int duracionMinutos;
    @Column(name = "publicado_en", nullable = false)
    private OffsetDateTime publicadoEn;

    protected Horario() {
    }

    public Horario(UUID id, UUID medicoId, UUID sedeId, LocalDate desde, LocalDate hasta, String diasSemana,
                   LocalTime horaInicio, LocalTime horaFin, int duracionMinutos, OffsetDateTime publicadoEn) {
        this.id = id;
        this.medicoId = medicoId;
        this.sedeId = sedeId;
        this.desde = desde;
        this.hasta = hasta;
        this.diasSemana = diasSemana;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
        this.duracionMinutos = duracionMinutos;
        this.publicadoEn = publicadoEn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMedicoId() {
        return medicoId;
    }

    public UUID getSedeId() {
        return sedeId;
    }

    public LocalDate getDesde() {
        return desde;
    }

    public LocalDate getHasta() {
        return hasta;
    }

    public String getDiasSemana() {
        return diasSemana;
    }

    public LocalTime getHoraInicio() {
        return horaInicio;
    }

    public LocalTime getHoraFin() {
        return horaFin;
    }

    public int getDuracionMinutos() {
        return duracionMinutos;
    }

    public OffsetDateTime getPublicadoEn() {
        return publicadoEn;
    }
}
