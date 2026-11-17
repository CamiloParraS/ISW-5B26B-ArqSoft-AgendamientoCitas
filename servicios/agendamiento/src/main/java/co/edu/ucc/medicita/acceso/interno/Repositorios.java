package co.edu.ucc.medicita.acceso.interno;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface InstitucionRepositorio extends JpaRepository<Institucion, String> {
}

interface ConsentimientoRepositorio extends JpaRepository<Consentimiento, UUID> {

    List<Consentimiento> findByPacienteIdOrderByOtorgadoEnDesc(UUID pacienteId);

    @Query("select count(c) > 0 from Consentimiento c where c.pacienteId = :pacienteId "
            + "and c.institucionCodigo = :institucion and c.revocadoEn is null and c.vigenteHasta > :ahora")
    boolean hayVigente(UUID pacienteId, String institucion, OffsetDateTime ahora);

    @Query("select c from Consentimiento c where c.pacienteId = :pacienteId and c.institucionCodigo = :institucion "
            + "and c.revocadoEn is null and c.vigenteHasta > :ahora order by c.otorgadoEn desc")
    List<Consentimiento> vigentes(UUID pacienteId, String institucion, OffsetDateTime ahora);

    Optional<Consentimiento> findByIdAndPacienteId(UUID id, UUID pacienteId);
}

interface BitacoraRepositorio extends JpaRepository<BitacoraAcceso, Long> {

    List<BitacoraAcceso> findTop200ByPacienteIdOrderByOcurridoEnDesc(UUID pacienteId);
}
