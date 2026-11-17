package co.edu.ucc.medicita.simuladores.hce;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import co.edu.ucc.medicita.simuladores.control.EstadoSimuladores;
import co.edu.ucc.medicita.simuladores.registro.Registro;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registra el servidor FHIR R4 (HAPI RestfulServer) como servlet en /fhir/*. */
@Configuration
public class ConfiguracionHce {

    /** Servidor FHIR simple de HAPI con los proveedores en memoria. */
    static class ServidorHce extends RestfulServer {

        private final transient List<IResourceProvider> proveedores;

        ServidorHce(FhirContext contexto, List<IResourceProvider> proveedores) {
            super(contexto);
            this.proveedores = proveedores;
        }

        @Override
        protected void initialize() {
            setResourceProviders(proveedores);
            setServerName("HCE simulada de MediCita");
            setServerVersion("1.0.0");
        }
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> servletFhir(List<IResourceProvider> proveedores) {
        ServletRegistrationBean<RestfulServer> registro = new ServletRegistrationBean<>(
                new ServidorHce(FhirContext.forR4Cached(), proveedores), "/fhir/*");
        registro.setName("hce-fhir");
        registro.setLoadOnStartup(1);
        return registro;
    }

    @Bean
    public FilterRegistrationBean<FiltroHce> filtroHce(EstadoSimuladores estado, Registro registro) {
        FilterRegistrationBean<FiltroHce> bean = new FilterRegistrationBean<>(new FiltroHce(estado, registro));
        bean.addUrlPatterns("/fhir/*");
        bean.setName("filtro-hce");
        return bean;
    }
}
