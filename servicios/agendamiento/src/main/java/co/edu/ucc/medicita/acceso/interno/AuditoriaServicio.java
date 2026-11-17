package co.edu.ucc.medicita.acceso.interno;

import co.edu.ucc.medicita.compartido.CorrelacionFiltro;
import co.edu.ucc.medicita.compartido.Tiempo;
import co.edu.ucc.medicita.compartido.UsuarioActual;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe la bitácora en su propia transacción (REQUIRES_NEW): la fila persiste aunque la consulta termine en
 * 403 o 404 y la excepción revierta cualquier transacción externa.
 */
@Service
public class AuditoriaServicio {

    private final BitacoraRepositorio bitacora;

    AuditoriaServicio(BitacoraRepositorio bitacora) {
        this.bitacora = bitacora;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(UsuarioActual usuario, String documento, UUID pacienteId, String resultado, String motivo) {
        bitacora.saveAndFlush(new BitacoraAcceso(Tiempo.ahora(), usuario.id().toString(), usuario.nombre(),
                usuario.institucion(), documento == null ? "" : documento, pacienteId, resultado, motivo,
                CorrelacionFiltro.actual()));
    }
}
