package co.edu.ucc.medicita.acceso.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "institucion", schema = "acceso")
class Institucion {

    @Id
    private String codigo;
    @Column(nullable = false)
    private String nombre;

    protected Institucion() {
    }

    String getCodigo() {
        return codigo;
    }

    String getNombre() {
        return nombre;
    }
}

@Entity
@Table(name = "consentimiento", schema = "acceso")
class Consentimiento {

    @Id
    private UUID id;
    @Column(name = "paciente_id", nullable = false)
    private UUID pacienteId;
    @Column(name = "institucion_codigo", nullable = false)
    private String institucionCodigo;
    @Column(name = "otorgado_en", nullable = false)
    private OffsetDateTime otorgadoEn;
    @Column(name = "vigente_hasta", nullable = false)
    private OffsetDateTime vigenteHasta;
    @Column(name = "revocado_en")
    private OffsetDateTime revocadoEn;

    protected Consentimiento() {
    }

    Consentimiento(UUID id, UUID pacienteId, String institucionCodigo, OffsetDateTime otorgadoEn,
                   OffsetDateTime vigenteHasta) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.institucionCodigo = institucionCodigo;
        this.otorgadoEn = otorgadoEn;
        this.vigenteHasta = vigenteHasta;
    }

    UUID getId() {
        return id;
    }

    UUID getPacienteId() {
        return pacienteId;
    }

    String getInstitucionCodigo() {
        return institucionCodigo;
    }

    OffsetDateTime getOtorgadoEn() {
        return otorgadoEn;
    }

    OffsetDateTime getVigenteHasta() {
        return vigenteHasta;
    }

    OffsetDateTime getRevocadoEn() {
        return revocadoEn;
    }

    void extenderHasta(OffsetDateTime hasta) {
        this.vigenteHasta = hasta;
    }

    void revocar(OffsetDateTime cuando) {
        if (revocadoEn == null) {
            revocadoEn = cuando;
        }
    }

    boolean vigente(OffsetDateTime ahora) {
        return revocadoEn == null && vigenteHasta.isAfter(ahora);
    }
}

/** Fila de la bitácora: sólo se inserta (trigger en BD impide UPDATE, DELETE y TRUNCATE). */
@Entity
@Table(name = "bitacora_acceso", schema = "acceso")
class BitacoraAcceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "ocurrido_en", nullable = false, updatable = false)
    private OffsetDateTime ocurridoEn;
    @Column(name = "usuario_sub", nullable = false, updatable = false)
    private String usuarioSub;
    @Column(name = "usuario_nombre", updatable = false)
    private String usuarioNombre;
    @Column(name = "institucion_codigo", updatable = false)
    private String institucionCodigo;
    @Column(name = "documento_consultado", nullable = false, updatable = false)
    private String documentoConsultado;
    @Column(name = "paciente_id", updatable = false)
    private UUID pacienteId;
    @Column(nullable = false, updatable = false)
    private String resultado;
    @Column(updatable = false)
    private String motivo;
    @Column(name = "correlacion_id", updatable = false)
    private String correlacionId;

    protected BitacoraAcceso() {
    }

    BitacoraAcceso(OffsetDateTime ocurridoEn, String usuarioSub, String usuarioNombre, String institucionCodigo,
                   String documentoConsultado, UUID pacienteId, String resultado, String motivo, String correlacionId) {
        this.ocurridoEn = ocurridoEn;
        this.usuarioSub = usuarioSub;
        this.usuarioNombre = usuarioNombre;
        this.institucionCodigo = institucionCodigo;
        this.documentoConsultado = documentoConsultado;
        this.pacienteId = pacienteId;
        this.resultado = resultado;
        this.motivo = motivo;
        this.correlacionId = correlacionId;
    }

    Long getId() {
        return id;
    }

    OffsetDateTime getOcurridoEn() {
        return ocurridoEn;
    }

    String getUsuarioNombre() {
        return usuarioNombre;
    }

    String getInstitucionCodigo() {
        return institucionCodigo;
    }

    String getResultado() {
        return resultado;
    }

    String getMotivo() {
        return motivo;
    }
}
