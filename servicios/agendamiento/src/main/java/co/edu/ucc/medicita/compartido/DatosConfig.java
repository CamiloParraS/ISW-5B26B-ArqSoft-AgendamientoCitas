package co.edu.ucc.medicita.compartido;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Datasource primario (escrituras) y datasource de sólo lectura sobre la réplica (reportes). */
@Configuration
public class DatosConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties propiedades) {
        return propiedades.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /** MEDICITA_REPLICA_URL (targetServerType=preferSecondary). Si no se define, usa el primario. */
    @Bean("replicaDataSource")
    public HikariDataSource replicaDataSource(DataSourceProperties propiedades,
                                              @Value("${medicita.replica.url:}") String urlReplica) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(urlReplica == null || urlReplica.isBlank() ? propiedades.getUrl() : urlReplica);
        ds.setUsername(propiedades.getUsername());
        ds.setPassword(propiedades.getPassword());
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(0);
        ds.setInitializationFailTimeout(-1);
        ds.setPoolName("replica-lectura");
        return ds;
    }
}
