package co.edu.ucc.medicita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** E3: toda consulta queda en una bitácora que sólo admite inserciones (trigger), y la IPS ve sólo 4 campos. */
class BitacoraSoloInsercionIT extends BaseIT {

    private static final String MENSAJE = "bitacora_acceso solo admite inserciones";

    private long filas() {
        return jdbc.sql("SELECT count(*) FROM acceso.bitacora_acceso").query(Long.class).single();
    }

    @Test
    void laBitacoraRechazaUpdateDeleteYTruncate() {
        jdbc.sql("""
                INSERT INTO acceso.bitacora_acceso(usuario_sub, usuario_nombre, institucion_codigo, documento_consultado, resultado, motivo)
                VALUES ('u-prueba', 'Prueba', 'IPS-NORTE', '123', 'DENEGADO', 'PRUEBA')""").update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE acceso.bitacora_acceso SET motivo = 'alterado'").update())
                .hasMessageContaining(MENSAJE);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM acceso.bitacora_acceso").update()).hasMessageContaining(MENSAJE);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE acceso.bitacora_acceso").update()).hasMessageContaining(MENSAJE);
        assertThat(filas()).isGreaterThan(0);
    }

    @Test
    void cadaConsultaQuedaAuditadaYLaIpsVeSoloCuatroCampos() throws Exception {
        // Ana (paciente de demostración) agenda una cita futura
        UUID cupo = crearCupos(ROSERO, SEDE_CENTRO, 1).get(0);
        var ana = paciente(ANA, "Ana Gómez", "1085000001");
        mvc.perform(post("/api/agenda/retenciones").with(ana).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cupoId\":\"" + cupo + "\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/agenda/citas").with(ana).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cupoId\":\"" + cupo + "\"}")).andExpect(status().isCreated());

        long antes = filas();
        var norte = usuario(UUID.fromString("d0000000-0000-4000-8000-000000000001"), "Marta Delgado", null, "IPS-NORTE", "administrativo_ips");
        var sur = usuario(UUID.fromString("d0000000-0000-4000-8000-000000000002"), "Pedro Insuasty", null, "IPS-SUR", "administrativo_ips");

        // IPS-NORTE tiene consentimiento: 200 y exactamente fecha, hora, sede, medico
        mvc.perform(get("/api/acceso/consultas/citas").param("documento", "1085000001").with(norte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].*", hasSize(4)))
                .andExpect(jsonPath("$[0].fecha").exists()).andExpect(jsonPath("$[0].hora").exists())
                .andExpect(jsonPath("$[0].sede").value("Sede Centro")).andExpect(jsonPath("$[0].medico").value("Carolina Rosero"));
        // IPS-SUR no tiene consentimiento: 403 (y la fila de auditoría persiste)
        mvc.perform(get("/api/acceso/consultas/citas").param("documento", "1085000001").with(sur))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SIN_CONSENTIMIENTO"));
        // Documento desconocido: 404 (también auditado)
        mvc.perform(get("/api/acceso/consultas/citas").param("documento", "0000000000").with(norte))
                .andExpect(status().isNotFound());
        // Un paciente no puede usar la consulta interinstitucional
        mvc.perform(get("/api/acceso/consultas/citas").param("documento", "1085000001").with(ana))
                .andExpect(status().isForbidden());

        assertThat(filas()).as("3 consultas = 3 filas (permitida, denegada, no encontrada)").isEqualTo(antes + 3);
        assertThat(jdbc.sql("SELECT count(*) FROM acceso.bitacora_acceso WHERE resultado = 'DENEGADO' "
                + "AND motivo = 'SIN_CONSENTIMIENTO' AND institucion_codigo = 'IPS-SUR'").query(Long.class).single()).isGreaterThan(0);

        // Ana ve quién consultó sus datos
        mvc.perform(get("/api/acceso/bitacora/mia").with(ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.resultado=='PERMITIDO')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.resultado=='DENEGADO')]").isNotEmpty());
    }
}
