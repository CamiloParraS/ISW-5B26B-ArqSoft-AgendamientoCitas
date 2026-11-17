package co.edu.ucc.medicita.integracionclinica.hce;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionHce {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }

    @Bean
    public IGenericClient clienteFhir(FhirContext contexto,
                                      @Value("${medicita.hce.fhir-url}") String url,
                                      @Value("${medicita.hce.connect-timeout-ms}") int conexionMs,
                                      @Value("${medicita.hce.read-timeout-ms}") int lecturaMs) {
        var fabrica = contexto.getRestfulClientFactory();
        fabrica.setServerValidationMode(ServerValidationModeEnum.NEVER);
        fabrica.setConnectTimeout(conexionMs);
        fabrica.setConnectionRequestTimeout(conexionMs);
        fabrica.setSocketTimeout(lecturaMs);
        IGenericClient cliente = contexto.newRestfulGenericClient(url);
        cliente.registerInterceptor(new CorrelacionInterceptor());
        return cliente;
    }

    @Bean
    public CircuitBreaker circuitBreakerHce(CircuitBreakerRegistry registro) {
        return registro.circuitBreaker("hce");
    }
}
