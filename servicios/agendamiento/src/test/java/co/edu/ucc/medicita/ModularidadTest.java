package co.edu.ucc.medicita;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Prueba de arquitectura (corre en "mvn package", por tanto en el build de la imagen): rompe el build si un
 * módulo usa clases internas de otro o si hay dependencias no declaradas o ciclos. También documenta los módulos.
 */
class ModularidadTest {

    private final ApplicationModules modulos = ApplicationModules.of(MediCitaAplicacion.class);

    @Test
    void lasFronterasEntreModulosSeRespetan() {
        modulos.forEach(m -> System.out.println("Módulo detectado: " + m.getIdentifier()));
        modulos.verify();
    }

    @Test
    void documentaLosModulos() {
        new Documenter(modulos).writeDocumentation();
    }
}
