package co.edu.ucc.medicita.notificaciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.ucc.medicita.notificaciones.dominio.Canal;
import co.edu.ucc.medicita.notificaciones.dominio.CanalNotificacion;
import co.edu.ucc.medicita.notificaciones.dominio.Destinatario;
import co.edu.ucc.medicita.notificaciones.dominio.EstadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.Mensaje;
import co.edu.ucc.medicita.notificaciones.dominio.ResultadoEnvio;
import co.edu.ucc.medicita.notificaciones.dominio.TipoNotificacion;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SelectorDeCanalesTest {

    private static final Canal W = Canal.of("WHATSAPP");
    private static final Canal S = Canal.of("SMS");
    private static final Canal C = Canal.of("CORREO");
    private static final MockEnvironment ENTORNO = new MockEnvironment()
            .withProperty("medicita.notificaciones.canales.whatsapp.contacto", "telefono")
            .withProperty("medicita.notificaciones.canales.sms.contacto", "telefono")
            .withProperty("medicita.notificaciones.canales.correo.contacto", "email");

    private static final NotificacionesProps PROPS =
            new NotificacionesProps(ZoneId.of("America/Bogota"), List.of("WHATSAPP", "SMS", "CORREO"), 5);

    private CanalNotificacion whatsapp;
    private CanalNotificacion sms;
    private CanalNotificacion correo;
    private RegistroEnvios registro;

    @BeforeEach
    void preparar() {
        whatsapp = adaptador(W);
        sms = adaptador(S);
        correo = adaptador(C);
        registro = mock(RegistroEnvios.class);
    }

    private static CanalNotificacion adaptador(Canal canal) {
        CanalNotificacion a = mock(CanalNotificacion.class);
        when(a.canal()).thenReturn(canal);
        return a;
    }

    private static Destinatario destinatario(Canal preferido, String telefono, String email) {
        return new Destinatario(UUID.randomUUID(), "Ana", telefono, email, preferido);
    }

    private static Mensaje mensaje(Destinatario d) {
        return new Mensaje(TipoNotificacion.CONFIRMACION, UUID.randomUUID(), d, "Carolina Rosero",
                "martes 18 de noviembre", "07:00", "Sede Centro", "corr-1");
    }

    @Test
    void ordenEsPreferidoLuegoRespaldoSinRepetir() {
        var selector = new SelectorDeCanales(List.of(whatsapp, sms, correo), PROPS, registro, ENTORNO);
        var d = destinatario(C, "+57300", "a@x.co");
        assertThat(selector.ordenDeIntento(d)).containsExactly(C, W, S);
    }

    @Test
    void omiteCanalesDeshabilitadosYSinDatosDeContacto() {
        var selector = new SelectorDeCanales(List.of(whatsapp, correo), PROPS, registro, ENTORNO); // SMS deshabilitado
        assertThat(selector.ordenDeIntento(destinatario(W, "+57300", "a@x.co")))
                .containsExactly(W, C);
        assertThat(selector.ordenDeIntento(destinatario(W, null, "a@x.co")))
                .containsExactly(C);
    }

    @Test
    void seDetieneEnElPrimerEnviado() {
        var d = destinatario(W, "+57300", "a@x.co");
        var m = mensaje(d);
        when(whatsapp.enviar(m)).thenReturn(ResultadoEnvio.enviado("wamid.1"));
        var selector = new SelectorDeCanales(List.of(whatsapp, sms, correo), PROPS, registro, ENTORNO);

        var entrega = selector.entregar(m, UUID.randomUUID());

        assertThat(entrega.entregado()).isTrue();
        assertThat(entrega.intentos()).hasSize(1);
        verify(sms, never()).enviar(any());
        verify(correo, never()).enviar(any());
    }

    @Test
    void plantillaRechazadaPasaASmsYCadaIntentoQuedaRegistrado() {
        var d = destinatario(W, "+57300", "a@x.co");
        var m = mensaje(d);
        var eventoId = UUID.randomUUID();
        when(whatsapp.enviar(m)).thenReturn(ResultadoEnvio.rechazado("código 132015"));
        when(sms.enviar(m)).thenReturn(ResultadoEnvio.enviado("sms-1"));
        var selector = new SelectorDeCanales(List.of(whatsapp, sms, correo), PROPS, registro, ENTORNO);

        var entrega = selector.entregar(m, eventoId);

        assertThat(entrega.entregado()).isTrue();
        assertThat(entrega.intentos()).extracting(SelectorDeCanales.Intento::canal)
                .containsExactly(W, S);
        verify(registro).registrar(eq(eventoId), eq(m.citaId()), eq(d.pacienteId()), eq(TipoNotificacion.CONFIRMACION),
                eq(W), any(ResultadoEnvio.class), eq("corr-1"));
        verify(registro).registrar(eq(eventoId), eq(m.citaId()), eq(d.pacienteId()), eq(TipoNotificacion.CONFIRMACION),
                eq(S), any(ResultadoEnvio.class), eq("corr-1"));
        verify(correo, never()).enviar(any());
    }

    @Test
    void excepcionDeAdaptadorCuentaComoFallidoYSigueConElRespaldo() {
        var d = destinatario(W, "+57300", "a@x.co");
        var m = mensaje(d);
        when(whatsapp.enviar(m)).thenThrow(new IllegalStateException("boom"));
        when(sms.enviar(m)).thenReturn(ResultadoEnvio.fallido("503"));
        when(correo.enviar(m)).thenReturn(ResultadoEnvio.enviado("<id@correo>"));
        var selector = new SelectorDeCanales(List.of(whatsapp, sms, correo), PROPS, registro, ENTORNO);

        var entrega = selector.entregar(m, null);

        assertThat(entrega.entregado()).isTrue();
        assertThat(entrega.intentos()).extracting(i -> i.resultado().estado())
                .containsExactly(EstadoEnvio.FALLIDO, EstadoEnvio.FALLIDO, EstadoEnvio.ENVIADO);
    }

    @Test
    void siTodosFallanNoHayEntrega() {
        var d = destinatario(S, "+57300", "a@x.co");
        var m = mensaje(d);
        when(whatsapp.enviar(m)).thenReturn(ResultadoEnvio.rechazado("x"));
        when(sms.enviar(m)).thenReturn(ResultadoEnvio.fallido("x"));
        when(correo.enviar(m)).thenReturn(ResultadoEnvio.fallido("x"));
        var selector = new SelectorDeCanales(List.of(whatsapp, sms, correo), PROPS, registro, ENTORNO);

        var entrega = selector.entregar(m, null);

        assertThat(entrega.entregado()).isFalse();
        assertThat(entrega.intentos()).hasSize(3);
    }
}
