package co.edu.ucc.medicita.prestadores.interno;

import co.edu.ucc.medicita.prestadores.MedicoResumen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SedeRepositorio extends JpaRepository<Sede, UUID> {
}

interface EspecialidadRepositorio extends JpaRepository<Especialidad, UUID> {
}

interface HorarioRepositorio extends JpaRepository<Horario, UUID> {
}

interface MedicoRepositorio extends JpaRepository<Medico, UUID> {

    String RESUMEN = "select new co.edu.ucc.medicita.prestadores.MedicoResumen(m.id, m.nombre, e.id, e.nombre, s.id, s.nombre) "
            + "from Medico m, Especialidad e, Sede s where e.id = m.especialidadId and s.id = m.sedeId";

    @Query(RESUMEN + " and m.id = :id")
    Optional<MedicoResumen> resumen(UUID id);

    @Query(RESUMEN + " and e.id = :especialidadId order by m.nombre")
    List<MedicoResumen> porEspecialidad(UUID especialidadId);

    @Query(RESUMEN + " order by m.nombre")
    List<MedicoResumen> todos();
}
