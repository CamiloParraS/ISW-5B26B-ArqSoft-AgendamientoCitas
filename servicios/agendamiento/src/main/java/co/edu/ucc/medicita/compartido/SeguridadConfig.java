package co.edu.ucc.medicita.compartido;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Resource server OAuth2: JWT validado con JWKS + issuer; roles desde realm_access.roles; sin sesión. */
@Configuration
@EnableMethodSecurity
public class SeguridadConfig {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${medicita.jwt.jwks-uri}") String jwksUri,
                                 @Value("${medicita.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    public SecurityFilterChain cadenaDeSeguridad(HttpSecurity http, JwtDecoder decoder, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint noAutenticado = (req, res, ex) -> {
            res.setHeader("WWW-Authenticate", "Bearer");
            escribir(json, res, 401, "NO_AUTENTICADO", "Debe iniciar sesión para continuar");
        };
        AccessDeniedHandler prohibido = (req, res, ex) ->
                escribir(json, res, 403, "PROHIBIDO", "No tiene permiso para esta operación");

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(noAutenticado).accessDeniedHandler(prohibido))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(noAutenticado)
                        .accessDeniedHandler(prohibido)
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(convertidorDeRoles())));
        return http.build();
    }

    private static Converter<Jwt, AbstractAuthenticationToken> convertidorDeRoles() {
        return jwt -> {
            List<GrantedAuthority> autoridades = UsuarioActual.roles(jwt).stream()
                    .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return new JwtAuthenticationToken(jwt, autoridades, jwt.getSubject());
        };
    }

    private static void escribir(ObjectMapper json, HttpServletResponse res, int estado, String codigo,
                                 String mensaje) throws IOException {
        res.setStatus(estado);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), Map.of("codigo", codigo, "mensaje", mensaje));
    }
}
