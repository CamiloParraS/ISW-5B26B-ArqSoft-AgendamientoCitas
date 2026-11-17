package co.edu.ucc.medicita.notificaciones.dominio;

import java.util.Locale;

/** Identificador abierto de canal (p. ej. WHATSAPP, SMS, CORREO). Cada adaptador declara el suyo. */
public record Canal(String codigo) {

    public Canal {
        if (codigo == null || codigo.isBlank()) throw new IllegalArgumentException("Canal sin código");
        codigo = codigo.trim().toUpperCase(Locale.ROOT);
    }

    public static Canal of(String codigo) {
        return new Canal(codigo);
    }

    @Override
    public String toString() {
        return codigo;
    }
}
