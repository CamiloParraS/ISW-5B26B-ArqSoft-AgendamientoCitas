package co.edu.ucc.medicita.integracionclinica.eventos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lee y valida el sobre JSON de los eventos v1. */
@Component
public class LectorEventos {

    private final ObjectMapper mapper;

    public LectorEventos(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Evento leer(byte[] cuerpo) {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(cuerpo);
        } catch (Exception e) {
            throw new EventoMalformadoException("JSON malformado", e);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new EventoMalformadoException("El mensaje no es un objeto JSON");
        }
        try {
            String tipo = texto(raiz, "tipo");
            if (raiz.path("version").asInt(-1) != 1) {
                throw new EventoMalformadoException("Versión de evento no soportada");
            }
            UUID eventoId = UUID.fromString(texto(raiz, "eventoId"));
            JsonNode datos = raiz.path("datos");
            if (!datos.isObject()) {
                throw new EventoMalformadoException("Falta 'datos'");
            }
            DatosCita contenido = switch (tipo) {
                case "CitaAgendada" -> mapper.treeToValue(datos, CitaAgendada.class);
                case "CitaCancelada" -> mapper.treeToValue(datos, CitaCancelada.class);
                case "CitaAtendida" -> mapper.treeToValue(datos, CitaAtendida.class);
                default -> throw new EventoMalformadoException("Tipo de evento desconocido: " + tipo);
            };
            validar(contenido);
            String correlacion = raiz.path("correlacionId").isTextual() ? raiz.get("correlacionId").asText() : null;
            return new Evento(eventoId, tipo, correlacion, contenido);
        } catch (EventoMalformadoException e) {
            throw e;
        } catch (Exception e) {
            throw new EventoMalformadoException("Evento inválido: " + e.getMessage(), e);
        }
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode v = nodo.path(campo);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new EventoMalformadoException("Falta el campo '" + campo + "'");
        }
        return v.asText();
    }

    private static void validar(DatosCita d) {
        boolean ok = switch (d) {
            case CitaAgendada a -> a.citaId() != null && a.pacienteId() != null && a.medicoId() != null
                    && a.inicio() != null && a.fin() != null;
            case CitaCancelada c -> c.citaId() != null && c.pacienteId() != null && c.inicio() != null;
            case CitaAtendida t -> t.citaId() != null && t.pacienteId() != null && t.medicoId() != null
                    && t.inicio() != null && t.fin() != null;
        };
        if (!ok) {
            throw new EventoMalformadoException("Faltan campos obligatorios en 'datos'");
        }
    }
}
