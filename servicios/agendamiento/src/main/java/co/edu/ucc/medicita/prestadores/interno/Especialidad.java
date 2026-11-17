package co.edu.ucc.medicita.prestadores.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "especialidad", schema = "prestadores")
public class Especialidad {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String nombre;

    protected Especialidad() {
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }
}
