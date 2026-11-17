package co.edu.ucc.medicita.simuladores.control;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Interruptores de falla de los tres sistemas externos simulados. */
@Component
public class EstadoSimuladores {

    public static final Set<String> PLANTILLAS_APROBADAS =
            Set.of("cita_confirmada", "cita_cancelada", "recordatorio_cita");

    private final AtomicBoolean hceCaida = new AtomicBoolean(false);
    private final AtomicBoolean smsCaida = new AtomicBoolean(false);
    private final Set<String> plantillasRechazadas = ConcurrentHashMap.newKeySet();

    public boolean hceCaida() {
        return hceCaida.get();
    }

    public void hceCaida(boolean valor) {
        hceCaida.set(valor);
    }

    public boolean smsCaida() {
        return smsCaida.get();
    }

    public void smsCaida(boolean valor) {
        smsCaida.set(valor);
    }

    public boolean plantillaRechazada(String nombre) {
        return plantillasRechazadas.contains(nombre);
    }

    public void plantillaRechazada(String nombre, boolean rechazada) {
        if (rechazada) {
            plantillasRechazadas.add(nombre);
        } else {
            plantillasRechazadas.remove(nombre);
        }
    }

    public Set<String> plantillasRechazadas() {
        return Set.copyOf(plantillasRechazadas);
    }
}
