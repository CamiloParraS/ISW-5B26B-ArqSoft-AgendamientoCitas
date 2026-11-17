package co.edu.ucc.medicita.prestadores.interno;

import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import co.edu.ucc.medicita.prestadores.MedicoResumen;
import co.edu.ucc.medicita.prestadores.SedeResumen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CatalogoPrestadoresImpl implements CatalogoPrestadores {

    private final MedicoRepositorio medicos;
    private final SedeRepositorio sedes;

    CatalogoPrestadoresImpl(MedicoRepositorio medicos, SedeRepositorio sedes) {
        this.medicos = medicos;
        this.sedes = sedes;
    }

    @Override
    public Optional<MedicoResumen> medico(UUID medicoId) {
        return medicos.resumen(medicoId);
    }

    @Override
    public List<MedicoResumen> medicosPorEspecialidad(UUID especialidadId) {
        return medicos.porEspecialidad(especialidadId);
    }

    @Override
    public Optional<SedeResumen> sede(UUID sedeId) {
        return sedes.findById(sedeId).map(s -> new SedeResumen(s.getId(), s.getNombre(), s.getDireccion()));
    }
}
