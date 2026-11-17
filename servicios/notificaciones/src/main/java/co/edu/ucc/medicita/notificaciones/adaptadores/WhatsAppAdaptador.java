package co.edu.ucc.medicita.notificaciones.adaptadores;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.CanalNotificacion;
import co.edu.ucc.medicita.notificaciones.dominio.EstadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptador de WhatsApp Business Cloud API (Meta). Sólo envía plantillas aprobadas, nunca texto libre
 * (fuera de la ventana de 24 h Meta lo rechaza). Se activa con medicita.notificaciones.canales.whatsapp.habilitado=true.
 */
@Component
@ConditionalOnProperty(prefix = "medicita.notificaciones.canales.whatsapp", name = "habilitado", havingValue = "true")
@EnableConfigurationProperties(WhatsAppAdaptador.Config.class)
public class WhatsAppAdaptador implements CanalNotificacion {

    static final Canal CANAL = Canal.of("WHATSAPP");

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAdaptador.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigurationProperties("medicita.notificaciones.canales.whatsapp")
    public record Config(String url, String phoneId, String token, String idioma,
                         Map<TipoNotificacion, String> plantillas) {
        public Config {
            if (idioma == null) idioma = "es_CO";
            if (plantillas == null) plantillas = Map.of();
        }
    }

    private final Config config;
    private final RestClient cliente;

    public WhatsAppAdaptador(Config config) {
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
            return ResultadoEnvio.rechazado("No hay plantilla aprobada de WhatsApp para " + mensaje.tipo());
        }
        List<Map<String, String>> parametros = new ArrayList<>();
        for (String valor : mensaje.variables().values()) {
            parametros.add(Map.of("type", "text", "text", valor));
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", plantilla);
        template.put("language", Map.of("code", config.idioma()));
        template.put("components", List.of(Map.of("type", "body", "parameters", parametros)));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("messaging_product", "whatsapp");
        cuerpo.put("to", mensaje.destinatario().telefono());
        cuerpo.put("type", "template");
        cuerpo.put("template", template);

        try {
            return cliente.post()
                    .uri("/v21.0/{phoneId}/messages", config.phoneId())
                    .header("Authorization", "Bearer " + config.token())
                    .headers(h -> {
                        if (mensaje.correlacionId() != null) h.set("X-Correlation-Id", mensaje.correlacionId());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .exchange((req, res) -> interpretar(res.getStatusCode(), res.bodyTo(String.class)));
        } catch (RestClientException e) {
            return ResultadoEnvio.fallido("WhatsApp no disponible: " + e.getMessage());
        }
    }

    private ResultadoEnvio interpretar(HttpStatusCode estado, String cuerpo) {
        if (estado.is2xxSuccessful()) {
            return ResultadoEnvio.enviado(extraerId(cuerpo));
        }
        if (estado.is5xxServerError() || estado.value() == 429) {
            return ResultadoEnvio.fallido("WhatsApp HTTP " + estado.value());
        }
        // 4xx: Meta rechazó (plantilla pausada 132015, fuera de ventana 131047, token inválido, etc.)
        String detalle = "WhatsApp HTTP " + estado.value();
        try {
            JsonNode error = JSON.readTree(cuerpo).path("error");
            detalle += ", código " + error.path("code").asText("?") + ": " + error.path("message").asText("");
        } catch (Exception e) {
            detalle += ": " + cuerpo;
        }
        log.warn("WhatsApp rechazó el mensaje: {}", detalle);
        return ResultadoEnvio.rechazado(detalle);
    }

    private static String extraerId(String cuerpo) {
        try {
            return JSON.readTree(cuerpo).path("messages").path(0).path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public EstadoEnvio consultarEstado(String idExterno) {
        // Meta informa la entrega por webhook; la API de envío no permite consultarla.
        return EstadoEnvio.DESCONOCIDO;
    }
}
