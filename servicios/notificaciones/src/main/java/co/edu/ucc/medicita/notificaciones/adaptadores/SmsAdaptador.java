package co.edu.ucc.medicita.notificaciones.adaptadores;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.CanalNotificacion;
import co.edu.ucc.medicita.notificaciones.dominio.EstadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Plantillas;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Adaptador de la pasarela SMS (HTTP). Texto plano en español desde la plantilla configurada. */
@Component
@ConditionalOnProperty(prefix = "medicita.notificaciones.canales.sms", name = "habilitado", havingValue = "true")
@EnableConfigurationProperties(SmsAdaptador.Config.class)
public class SmsAdaptador implements CanalNotificacion {

    static final Canal CANAL = Canal.of("SMS");

    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigurationProperties("medicita.notificaciones.canales.sms")
    public record Config(String url, Map<TipoNotificacion, String> plantillas) {
        public Config {
            if (plantillas == null) plantillas = Map.of();
        }
    }

    private final Config config;
    private final RestClient cliente;

    public SmsAdaptador(Config config) {
        this.config = config;
        this.cliente = ClienteHttp.crear(config.url());
    }

    @Override
    public Canal canal() {
        return CANAL;
    }

    @Override
    public ResultadoEnvio enviar(Mensaje mensaje) {
        String plantilla = config.plantillas().get(mensaje.tipo());
        if (plantilla == null) {
            return ResultadoEnvio.rechazado("No hay plantilla de SMS para " + mensaje.tipo());
        }
        String texto = Plantillas.rellenar(plantilla, mensaje.variablesConNombre());
        try {
            return cliente.post()
                    .uri("/v1/mensajes")
                    .headers(h -> {
                        if (mensaje.correlacionId() != null) h.set("X-Correlation-Id", mensaje.correlacionId());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("para", mensaje.destinatario().telefono(), "texto", texto))
                    .exchange((req, res) -> interpretar(res.getStatusCode(), res.bodyTo(String.class)));
        } catch (RestClientException e) {
            return ResultadoEnvio.fallido("Pasarela SMS no disponible: " + e.getMessage());
        }
    }

    private ResultadoEnvio interpretar(HttpStatusCode estado, String cuerpo) {
        if (estado.is2xxSuccessful()) {
            try {
                JsonNode n = JSON.readTree(cuerpo);
                return ResultadoEnvio.enviado(n.path("id").asText(null));
            } catch (Exception e) {
                return ResultadoEnvio.enviado(null);
            }
        }
        if (estado.is5xxServerError() || estado.value() == 429) {
            return ResultadoEnvio.fallido("SMS HTTP " + estado.value());
        }
        return ResultadoEnvio.rechazado("SMS HTTP " + estado.value() + ": " + cuerpo);
    }

    @Override
    public EstadoEnvio consultarEstado(String idExterno) {
        try {
            String cuerpo = cliente.get().uri("/v1/mensajes/{id}", idExterno).retrieve().body(String.class);
            String estado = JSON.readTree(cuerpo).path("estado").asText("");
            return switch (estado) {
                case "ENVIADO", "ENTREGADO" -> EstadoEnvio.ENVIADO;
                case "RECHAZADO" -> EstadoEnvio.RECHAZADO;
                case "FALLIDO" -> EstadoEnvio.FALLIDO;
                default -> EstadoEnvio.DESCONOCIDO;
            };
        } catch (Exception e) {
            return EstadoEnvio.DESCONOCIDO;
        }
    }
}
