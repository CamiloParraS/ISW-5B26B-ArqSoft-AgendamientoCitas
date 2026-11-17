package co.edu.ucc.medicita;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Cada módulo es dueño de su esquema: sus entidades mapean sólo tablas de su esquema, nadie fuera del módulo toca
 * sus entidades ni repositorios, y ningún SQL (Java o migraciones) menciona el esquema de otro módulo.
 */
class PropiedadDeTablasTest {

    private static final String BASE = "co.edu.ucc.medicita";
    private static final List<String> MODULOS_CON_TABLAS = List.of("prestadores", "agenda", "acceso");

    private final JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(BASE);

    @Test
    void cadaEntidadUsaElEsquemaDeSuModulo() {
        ArchRuleDefinition.classes().that().areAnnotatedWith(Entity.class).should(new ArchCondition<JavaClass>(
                "mapear una tabla del esquema del módulo que la contiene") {
            @Override
            public void check(JavaClass c, ConditionEvents eventos) {
                String modulo = c.getPackageName().substring(BASE.length() + 1).split("\\.")[0];
                Table tabla = c.getAnnotationOfType(Table.class);
                boolean ok = tabla != null && modulo.equals(tabla.schema());
                eventos.add(new SimpleConditionEvent(c, ok, c.getName() + " debe declarar @Table(schema = \"" + modulo + "\")"));
            }
        }).check(clases);
    }

    @Test
    void nadieFueraDelModuloUsaSusEntidadesNiRepositorios() {
        for (String modulo : MODULOS_CON_TABLAS) {
            DescribedPredicate<JavaClass> persistenciaDelModulo = DescribedPredicate.describe(
                    "entidad o repositorio de " + modulo,
                    c -> c.getPackageName().startsWith(BASE + "." + modulo)
                            && (c.isAnnotatedWith(Entity.class)
                            || c.isAssignableTo(org.springframework.data.repository.Repository.class)));
            ArchRuleDefinition.noClasses().that().resideOutsideOfPackage(BASE + "." + modulo + "..")
                    .should().dependOnClassesThat(persistenciaDelModulo).check(clases);
        }
    }

    @Test
    void ningunSqlDeJavaMencionaElEsquemaDeOtroModulo() throws IOException {
        for (String modulo : MODULOS_CON_TABLAS) {
            Path carpeta = Path.of("src/main/java", BASE.replace('.', '/'), modulo);
            Pattern ajeno = esquemasAjenos(modulo);
            try (Stream<Path> archivos = Files.walk(carpeta)) {
                for (Path p : archivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                    Matcher m = ajeno.matcher(Files.readString(p));
                    assertThat(m.find()).as("%s menciona SQL contra otro esquema: %s", p, m.find(0) ? m.group() : "").isFalse();
                }
            }
        }
    }

    @Test
    void lasMigracionesSoloTocanElEsquemaDeSuModulo() throws IOException {
        try (Stream<Path> archivos = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path p : archivos.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String nombre = p.getFileName().toString();
                String modulo = nombre.replaceAll("^V\\d+__([a-z]+)_.*$", "$1");
                assertThat(MODULOS_CON_TABLAS).as("prefijo de módulo en %s", nombre).contains(modulo);
                Matcher m = esquemasAjenos(modulo).matcher(Files.readString(p));
                assertThat(m.find()).as("%s menciona otro esquema: %s", nombre, m.find(0) ? m.group() : "").isFalse();
            }
        }
    }

    /** Detecta "FROM|JOIN|INTO|UPDATE|TABLE|REFERENCES|ON <esquema ajeno>.<objeto>" y "<esquema ajeno>.<tabla>" en SQL. */
    private static Pattern esquemasAjenos(String modulo) {
        String otros = MODULOS_CON_TABLAS.stream().filter(m -> !m.equals(modulo)).reduce((a, b) -> a + "|" + b).orElse("x");
        return Pattern.compile("(?i)\\b(from|join|into|update|table|references|on|delete\\s+from)\\s+(" + otros + ")\\.[a-z_]+");
    }
}
