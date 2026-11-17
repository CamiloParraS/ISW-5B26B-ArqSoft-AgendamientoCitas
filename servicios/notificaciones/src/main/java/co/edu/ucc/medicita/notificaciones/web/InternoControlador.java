package co.edu.ucc.medicita.notificaciones.web;

import co.edu.ucc.medicita.notificaciones.aplicacion.NotificacionesProps;
import co.edu.ucc.medicita.notificaciones.aplicacion.ServicioRecordatorios;
import co.edu.ucc.medicita.notificaciones.aplicacion.ServicioRecordatorios.Resumen;
import co.edu.ucc.medicita.notificaciones.persistencia.RegistroEnviosJdbc;
import co.edu.ucc.medicita.notificaciones.persistencia.RegistroEnviosJdbc.EnvioVista;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints internos (red interna; el gateway no los enruta). */
@RestController
@RequestMapping("/interno")
public class InternoControlador {

    private final ServicioRecordatorios recordatorios;
    private final RegistroEnviosJdbc envios;
    private final NotificacionesProps props;

    public InternoControlador(ServicioRecordatorios recordatorios, RegistroEnviosJdbc envios, NotificacionesProps props) {
        this.recordatorios = recordatorios;
        this.envios = envios;
        this.props = props;
    }

    /** Dispara el recordatorio masivo; por defecto, las citas de mañana (America/Bogota). */
    @PostMapping("/recordatorios/ejecucion")
    public Resumen ejecutarRecordatorios(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return recordatorios.ejecutar(fecha != null ? fecha : LocalDate.now(props.zona()).plusDays(1));
    }

    @GetMapping("/envios")
    public List<EnvioVista> envios(@RequestParam UUID citaId) {
        return envios.porCita(citaId);
    }
}
