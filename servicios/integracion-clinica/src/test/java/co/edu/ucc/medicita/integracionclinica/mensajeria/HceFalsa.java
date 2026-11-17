package co.edu.ucc.medicita.integracionclinica.mensajeria;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Resource;

/** Servidor FHIR mínimo en memoria para la prueba de integración (create/update condicional por identifier). */
class HceFalsa implements AutoCloseable {

    private final FhirContext ctx = FhirContext.forR4Cached();
    private final HttpServer servidor;
    private final AtomicBoolean caida = new AtomicBoolean(false);
    private final Map<String, Map<String, Resource>> recursos = new ConcurrentHashMap<>();
    final List<String> correlaciones = new CopyOnWriteArrayList<>();

    HceFalsa() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/fhir", this::atender);
        servidor.start();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/fhir";
    }

    void caida(boolean valor) {
        caida.set(valor);
    }

    synchronized List<Resource> de(String tipo) {
        return new ArrayList<>(recursos.getOrDefault(tipo, Map.of()).values());
    }

    synchronized void precargar(Resource recurso) {
        recurso.setId(UUID.randomUUID().toString());
        recursos.computeIfAbsent(recurso.fhirType(), k -> new ConcurrentHashMap<>()).put(recurso.getIdElement().getIdPart(), recurso);
    }

    private synchronized void atender(HttpExchange ex) throws IOException {
        try {
            String corr = ex.getRequestHeaders().getFirst("X-Correlation-Id");
            if (corr != null) {
                correlaciones.add(corr);
            }
            if (caida.get()) {
                responder(ex, 503, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\",\"code\":\"transient\"}]}", null);
                return;
            }
            String ruta = ex.getRequestURI().getRawPath().substring("/fhir/".length());
            String tipo = ruta.contains("/") ? ruta.substring(0, ruta.indexOf('/')) : ruta;
            String consulta = ex.getRequestURI().getRawQuery();
            String metodo = ex.getRequestMethod();
            String identificador = identificadorDe(consulta);
            if (metodo.equals("GET")) {
                Bundle b = new Bundle();
                b.setType(Bundle.BundleType.SEARCHSET);
                buscar(tipo, identificador).forEach(r -> b.addEntry().setResource(r));
                responder(ex, 200, ctx.newJsonParser().encodeResourceToString(b), null);
            } else if (metodo.equals("POST")) {
                Resource nuevo = (Resource) ctx.newJsonParser().parseResource(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String condicion = ex.getRequestHeaders().getFirst("If-None-Exist");
                List<Resource> existentes = condicion == null ? List.of() : buscar(tipo, identificadorDe(condicion));
                if (!existentes.isEmpty()) {
                    responder(ex, 200, null, ubicacion(existentes.get(0)));
                } else {
                    nuevo.setId(UUID.randomUUID().toString());
                    recursos.computeIfAbsent(tipo, k -> new ConcurrentHashMap<>()).put(nuevo.getIdElement().getIdPart(), nuevo);
                    responder(ex, 201, null, ubicacion(nuevo));
                }
            } else if (metodo.equals("PUT")) {
                Resource nuevo = (Resource) ctx.newJsonParser().parseResource(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                List<Resource> existentes = buscar(tipo, identificador);
                String id = existentes.isEmpty() ? UUID.randomUUID().toString() : existentes.get(0).getIdElement().getIdPart();
                nuevo.setId(id);
                recursos.computeIfAbsent(tipo, k -> new ConcurrentHashMap<>()).put(id, nuevo);
                responder(ex, existentes.isEmpty() ? 201 : 200, null, ubicacion(nuevo));
            } else {
                responder(ex, 405, null, null);
            }
        } catch (RuntimeException e) {
            responder(ex, 400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}", null);
        }
    }

    private List<Resource> buscar(String tipo, String identificador) {
        List<Resource> todos = new ArrayList<>(recursos.getOrDefault(tipo, Map.of()).values());
        if (identificador == null) {
            return todos;
        }
        return todos.stream().filter(r -> tieneIdentificador(r, identificador)).toList();
    }

    private boolean tieneIdentificador(Resource r, String sistemaValor) {
        List<Identifier> ids = switch (r) {
            case org.hl7.fhir.r4.model.Patient p -> p.getIdentifier();
            case org.hl7.fhir.r4.model.Appointment a -> a.getIdentifier();
            case org.hl7.fhir.r4.model.Encounter e -> e.getIdentifier();
            default -> List.of();
        };
        return ids.stream().anyMatch(i -> (i.getSystem() + "|" + i.getValue()).equals(sistemaValor));
    }

    private static String identificadorDe(String consulta) {
        if (consulta == null) {
            return null;
        }
        String q = consulta.contains("?") ? consulta.substring(consulta.indexOf('?') + 1) : consulta;
        for (String par : q.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2 && kv[0].equals("identifier")) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String ubicacion(Resource r) {
        return url() + "/" + r.fhirType() + "/" + r.getIdElement().getIdPart() + "/_history/1";
    }

    private void responder(HttpExchange ex, int estado, String cuerpo, String ubicacion) throws IOException {
        byte[] bytes = cuerpo == null ? new byte[0] : cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/fhir+json");
        if (ubicacion != null) {
            ex.getResponseHeaders().add("Location", ubicacion);
        }
        ex.sendResponseHeaders(estado, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
