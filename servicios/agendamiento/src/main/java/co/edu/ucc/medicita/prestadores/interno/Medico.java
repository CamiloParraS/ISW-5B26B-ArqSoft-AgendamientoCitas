package co.edu.ucc.medicita.prestadores.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "medico", schema = "prestadores")
public class Medico {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String nombre;
    @Column(name = "especialidad_id", nullable = false)
    private UUID especialidadId;
    @Column(name = "sede_id", nullable = false)
    private UUID sedeId;

    protected Medico() {
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public UUID getEspecialidadId() {
        return especialidadId;
    }

    public UUID getSedeId() {
        return sedeId;
    }
}
