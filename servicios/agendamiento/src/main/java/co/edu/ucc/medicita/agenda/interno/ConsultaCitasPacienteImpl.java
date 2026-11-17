package co.edu.ucc.medicita.agenda.interno;

import co.edu.ucc.medicita.agenda.CitaResumen;
import co.edu.ucc.medicita.agenda.ConsultaCitasPaciente;
import co.edu.ucc.medicita.prestadores.CatalogoPrestadores;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class ConsultaCitasPacienteImpl implements ConsultaCitasPaciente {

    private final JdbcClient jdbc;
    private final Citas citas;
    private final CatalogoPrestadores catalogo;

    ConsultaCitasPacienteImpl(JdbcClient jdbc, Citas citas, CatalogoPrestadores catalogo) {
        this.jdbc = jdbc;
        this.citas = citas;
        this.catalogo = catalogo;
    }

    @Override
    public Optional<UUID> pacienteIdPorDocumento(String documento) {
        return jdbc.sql("SELECT id FROM agenda.paciente WHERE documento = :d").param("d", documento)
                .query(UUID.class).optional();
    }

    @Override
    public List<CitaResumen> citasVigentesDe(UUID pacienteId) {
        OffsetDateTime ahora = OffsetDateTime.now();
        return citas.delPaciente(pacienteId).stream()
                .filter(c -> "CONFIRMADA".equals(c.estado()) && c.inicio().isAfter(ahora))
                .sorted(java.util.Comparator.comparing(CitaRow::inicio))
                .map(c -> new CitaResumen(c.id(), c.inicio(), c.fin(),
                        catalogo.sede(c.sedeId()).map(s -> s.nombre()).orElse(null),
                        catalogo.medico(c.medicoId()).map(m -> m.nombre()).orElse(null)))
                .toList();
    }
}
