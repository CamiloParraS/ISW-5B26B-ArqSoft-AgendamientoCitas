package co.edu.ucc.medicita.notificaciones.adaptadores;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Cliente HTTP común de los adaptadores: timeouts de conexión 2 s y lectura 5 s. */
final class ClienteHttp {

    private ClienteHttp() {
    }

    static RestClient crear(String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(5000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }
}
