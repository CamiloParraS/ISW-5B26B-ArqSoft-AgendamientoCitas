package co.edu.ucc.medicita.prestadores.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "sede", schema = "prestadores")
public class Sede {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String nombre;
    @Column(nullable = false)
    private String direccion;

    protected Sede() {
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDireccion() {
        return direccion;
    }
}
