package co.edu.ucc.medicita.simuladores.registro;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bitácora en memoria (últimas 1000 llamadas por sistema) con la correlación recibida. */
@Component
public class Registro {

    private static final int MAXIMO = 1000;
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final List<Map<String, Object>> whatsapp = new ArrayList<>();
    private final List<Map<String, Object>> sms = new ArrayList<>();
    private final List<Map<String, Object>> hce = new ArrayList<>();

    public void whatsapp(String correlacionId, Map<String, Object> datos) {
        agregar(whatsapp, correlacionId, datos);
    }

    public void sms(String correlacionId, Map<String, Object> datos) {
        agregar(sms, correlacionId, datos);
    }

    public void hce(String correlacionId, Map<String, Object> datos) {
        agregar(hce, correlacionId, datos);
    }

    public List<Map<String, Object>> whatsapp() {
        return copia(whatsapp);
    }

    public List<Map<String, Object>> sms() {
        return copia(sms);
    }

    public List<Map<String, Object>> hce() {
        return copia(hce);
    }

    private synchronized void agregar(List<Map<String, Object>> lista, String correlacionId, Map<String, Object> datos) {
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("ocurridoEn", OffsetDateTime.now(BOGOTA).withNano(0).toString());
        entrada.put("correlacionId", correlacionId);
        entrada.putAll(datos);
        lista.add(entrada);
        if (lista.size() > MAXIMO) {
            lista.remove(0);
        }
    }

    private synchronized List<Map<String, Object>> copia(List<Map<String, Object>> lista) {
        return new ArrayList<>(lista);
    }
}
