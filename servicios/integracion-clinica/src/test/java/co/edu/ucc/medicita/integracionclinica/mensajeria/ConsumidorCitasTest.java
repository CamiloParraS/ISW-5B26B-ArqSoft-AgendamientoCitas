package co.edu.ucc.medicita.integracionclinica.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

class ConsumidorCitasTest {

    private static Message base() {
        return MessageBuilder.withBody("{}".getBytes()).build();
    }

    @Test
    void losReintentosPorDisponibilidadNoConsumenXIntentos() {
        Message m = base();
        for (int i = 0; i < 20; i++) {
            m = ConsumidorCitas.copiaParaReintento(m, true);
        }
        assertThat(ConsumidorCitas.entero(m, "x-intentos")).isZero();
        assertThat(ConsumidorCitas.entero(m, "x-reintentos-disponibilidad")).isEqualTo(20);
    }

    @Test
    void losFallosPermanentesSiIncrementanXIntentosSinTocarLaDisponibilidad() {
        Message m = ConsumidorCitas.copiaParaReintento(ConsumidorCitas.copiaParaReintento(base(), true), true);
        m = ConsumidorCitas.copiaParaReintento(m, false);
        assertThat(ConsumidorCitas.entero(m, "x-intentos")).isEqualTo(1);
        assertThat(ConsumidorCitas.entero(m, "x-reintentos-disponibilidad")).isEqualTo(2);
    }
}
