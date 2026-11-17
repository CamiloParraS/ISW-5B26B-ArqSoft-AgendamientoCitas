package co.edu.ucc.medicita.notificaciones.adaptadores;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.CanalNotificacion;
import co.edu.ucc.medicita.notificaciones.dominio.EstadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.Plantillas;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Adaptador de correo: SMTP con JavaMailSender (en el esqueleto, hacia Mailpit). */
@Component
@ConditionalOnProperty(prefix = "medicita.notificaciones.canales.correo", name = "habilitado", havingValue = "true")
@EnableConfigurationProperties(CorreoAdaptador.Config.class)
public class CorreoAdaptador implements CanalNotificacion {

    static final Canal CANAL = Canal.of("CORREO");

    @ConfigurationProperties("medicita.notificaciones.canales.correo")
    public record Config(String remitente, Map<TipoNotificacion, Plantilla> plantillas) {
        public Config {
            if (remitente == null) remitente = "notificaciones@medicita.test";
            if (plantillas == null) plantillas = Map.of();
        }

        public record Plantilla(String asunto, String texto) {
        }
    }

    private final Config config;
    private final JavaMailSender correo;

    public CorreoAdaptador(Config config, JavaMailSender correo) {
        this.config = config;
        this.correo = correo;
    }

    @Override
    public Canal canal() {
        return CANAL;
    }

    @Override
    public ResultadoEnvio enviar(Mensaje mensaje) {
        Config.Plantilla plantilla = config.plantillas().get(mensaje.tipo());
        if (plantilla == null) {
            return ResultadoEnvio.rechazado("No hay plantilla de correo para " + mensaje.tipo());
        }
        try {
            MimeMessage mime = correo.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(mime, "UTF-8");
            h.setFrom(config.remitente());
            h.setTo(mensaje.destinatario().email());
            h.setSubject(Plantillas.rellenar(plantilla.asunto(), mensaje.variablesConNombre()));
            h.setText(Plantillas.rellenar(plantilla.texto(), mensaje.variablesConNombre()), false);
            if (mensaje.correlacionId() != null) mime.setHeader("X-Correlation-Id", mensaje.correlacionId());
            correo.send(mime);
            return ResultadoEnvio.enviado(mime.getMessageID());
        } catch (MessagingException | MailParseException e) {
            return ResultadoEnvio.rechazado("Correo inválido: " + e.getMessage());
        } catch (MailException e) {
            return ResultadoEnvio.fallido("SMTP no disponible: " + e.getMessage());
        }
    }

    @Override
    public EstadoEnvio consultarEstado(String idExterno) {
        // SMTP no ofrece consulta de estado
        return EstadoEnvio.DESCONOCIDO;
    }
}
