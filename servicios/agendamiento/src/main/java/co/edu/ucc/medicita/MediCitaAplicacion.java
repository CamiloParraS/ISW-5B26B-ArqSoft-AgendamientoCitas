package co.edu.ucc.medicita;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** API de Agendamiento: monolito modular (Spring Modulith) con prestadores, agenda y acceso. */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MediCitaAplicacion {

    public static void main(String[] args) {
        SpringApplication.run(MediCitaAplicacion.class, args);
    }
}
