package co.edu.ucc.medicita.simuladores.registro;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inspección de lo que cada sistema externo recibió (más antiguo primero). */
@RestController
@RequestMapping("/registro")
public class RegistroController {

    private final Registro registro;

    public RegistroController(Registro registro) {
        this.registro = registro;
    }

    @GetMapping("/whatsapp")
    public List<Map<String, Object>> whatsapp() {
        return registro.whatsapp();
    }

    @GetMapping("/sms")
    public List<Map<String, Object>> sms() {
        return registro.sms();
    }

    @GetMapping("/hce")
    public List<Map<String, Object>> hce() {
        return registro.hce();
    }
}
