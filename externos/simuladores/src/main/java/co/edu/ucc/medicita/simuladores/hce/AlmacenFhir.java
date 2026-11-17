package co.edu.ucc.medicita.simuladores.hce;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Resource;

/** Almacén en memoria de un tipo de recurso FHIR, con operaciones condicionales por identifier. */
public class AlmacenFhir<T extends Resource> {

    /** Resultado de una escritura: el recurso guardado y si se creó uno nuevo. */
    public record Escritura<R>(R recurso, boolean creado) {
    }

    private final String tipo;
    private final Function<T, List<Identifier>> identificadores;
    private final Map<String, T> recursos = new LinkedHashMap<>();
    private final Map<String, Integer> versiones = new LinkedHashMap<>();

    public AlmacenFhir(String tipo, Function<T, List<Identifier>> identificadores) {
        this.tipo = tipo;
        this.identificadores = identificadores;
    }

    public synchronized int total() {
        return recursos.size();
    }

    public synchronized Optional<T> leer(String id) {
        return Optional.ofNullable(recursos.get(id));
    }

    /** Busca por identifier; sistema o valor nulos actúan como comodín. Sin filtro devuelve todos. */
    public synchronized List<T> buscar(String sistema, String valor) {
        List<T> salida = new ArrayList<>();
        for (T r : recursos.values()) {
            if (sistema == null && valor == null || coincide(r, sistema, valor)) {
                salida.add(r);
            }
        }
        return salida;
    }

    /** Create; con condición (If-None-Exist) es idempotente por identifier. */
    public synchronized Escritura<T> crear(T nuevo, String condicion) {
        if (condicion != null) {
            List<T> existentes = buscarPorCondicion(condicion);
            if (existentes.size() > 1) {
                throw new PreconditionFailedException("La condición coincide con " + existentes.size() + " recursos");
            }
            if (existentes.size() == 1) {
                return new Escritura<>(existentes.get(0), false);
            }
        }
        return new Escritura<>(guardar(UUID.randomUUID().toString(), nuevo), true);
    }

    /** Update por id o condicional por identifier (si no hay coincidencia, crea). */
    public synchronized Escritura<T> actualizar(T nuevo, String id, String condicion) {
        if (condicion != null) {
            List<T> existentes = buscarPorCondicion(condicion);
            if (existentes.size() > 1) {
                throw new PreconditionFailedException("La condición coincide con " + existentes.size() + " recursos");
            }
            if (existentes.isEmpty()) {
                return new Escritura<>(guardar(UUID.randomUUID().toString(), nuevo), true);
            }
            return new Escritura<>(guardar(existentes.get(0).getIdElement().getIdPart(), nuevo), false);
        }
        if (id == null) {
            throw new InvalidRequestException("Falta el id del recurso a actualizar");
        }
        boolean existia = recursos.containsKey(id);
        return new Escritura<>(guardar(id, nuevo), !existia);
    }

    public synchronized T precargar(String id, T recurso) {
        return guardar(id, recurso);
    }

    private T guardar(String id, T recurso) {
        int version = versiones.merge(id, 1, Integer::sum);
        recurso.setId(new IdType(tipo, id, String.valueOf(version)));
        recursos.put(id, recurso);
        return recurso;
    }

    private List<T> buscarPorCondicion(String condicion) {
        String consulta = condicion.contains("?") ? condicion.substring(condicion.indexOf('?') + 1) : condicion;
        for (String par : consulta.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2 && kv[0].equals("identifier")) {
                String valor = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                int barra = valor.indexOf('|');
                return barra < 0 ? buscar(null, valor) : buscar(valor.substring(0, barra), valor.substring(barra + 1));
            }
        }
        throw new InvalidRequestException("Solo se soportan condiciones por 'identifier' (sistema|valor)");
    }

    private boolean coincide(T recurso, String sistema, String valor) {
        return identificadores.apply(recurso).stream().anyMatch(i ->
                (sistema == null || sistema.equals(i.getSystem())) && (valor == null || valor.equals(i.getValue())));
    }
}
