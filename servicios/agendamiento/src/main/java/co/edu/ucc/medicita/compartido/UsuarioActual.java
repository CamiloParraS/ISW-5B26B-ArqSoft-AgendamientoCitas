package co.edu.ucc.medicita.compartido;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** Usuario autenticado, derivado de los claims del token de Keycloak. */
public record UsuarioActual(UUID id, String nombre, String documento, String telefono, String institucion,
                            Set<String> roles) {

    public static UsuarioActual de(Jwt jwt) {
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_INVALIDO", "El identificador del usuario no es válido");
        }
        String nombre = jwt.getClaimAsString("name");
        if (nombre == null || nombre.isBlank()) {
            nombre = jwt.getClaimAsString("preferred_username");
        }
        if (nombre == null || nombre.isBlank()) {
            nombre = jwt.getSubject();
        }
        return new UsuarioActual(id, nombre, jwt.getClaimAsString("documento"), jwt.getClaimAsString("telefono"),
                jwt.getClaimAsString("institucion"), roles(jwt));
    }

    public static Set<String> roles(Jwt jwt) {
        Object acceso = jwt.getClaim("realm_access");
        if (acceso instanceof Map<?, ?> mapa && mapa.get("roles") instanceof List<?> lista) {
            return Set.copyOf(lista.stream().map(String::valueOf).toList());
        }
        return Set.of();
    }
}
