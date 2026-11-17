package co.edu.ucc.medicita.notificaciones;

import co.edu.ucc.medicita.notificaciones.aplicacion.NotificacionesProps;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(NotificacionesProps.class)
@EnableScheduling
public class NotificacionesAplicacion {

    public static void main(String[] args) {
        SpringApplication.run(NotificacionesAplicacion.class, args);
    }
}
