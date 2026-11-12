"""D-2 · Si cae el proveedor de correo/SMS, se sigue agendando. Y el proveedor nunca recibe datos clínicos."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from django.test import TestCase, override_settings

from agenda import aplicacion as agenda
from agenda import dominio as d
from agenda.models import Cita
from atencion_clinica import aplicacion as clinica
from identidad import api as identidad
from notificaciones import aplicacion as notificaciones
from notificaciones import dominio as nd
from notificaciones.models import Envio

from .utilidades import cupo, usuario


class ProveedorFalso:
    def __init__(self):
        self.recibidos = []
        recibidos = self.recibidos

        class Manejador(BaseHTTPRequestHandler):
            def do_POST(self):
                recibidos.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(202)
                self.end_headers()

            def log_message(self, *a):
                pass

        self.servidor = ThreadingHTTPServer(("127.0.0.1", 0), Manejador)
        self.url = f"http://127.0.0.1:{self.servidor.server_address[1]}"
        threading.Thread(target=self.servidor.serve_forever, daemon=True).start()

    def cerrar(self):
        self.servidor.shutdown()
        self.servidor.server_close()


class DegradacionElegante(TestCase):
    def setUp(self):
        self.medico = usuario("medico", identidad.MEDICO)
        self.paciente = usuario("paciente", identidad.PACIENTE)

    def test_con_el_proveedor_caido_la_reserva_se_confirma_y_el_envio_queda_pendiente(self):
        # En pruebas el proveedor apunta a un puerto cerrado: está "caído".
        cita, creada = agenda.reservar(self.paciente.id, cupo(self.medico.id)["id"], "k")
        self.assertTrue(creada)
        self.assertEqual(Cita.objects.get(id=cita["id"]).estado, d.CONFIRMADA)
        resultado = notificaciones.enviar_pendientes()
        self.assertEqual(resultado, {"enviados": 0, "pendientes": 1})
        envio = Envio.objects.get(cita_id=cita["id"])
        self.assertEqual((envio.estado, envio.intentos), (nd.PENDIENTE, 1))

    def test_cuando_el_proveedor_vuelve_el_barrido_reintenta_y_envia(self):
        cita, _ = agenda.reservar(self.paciente.id, cupo(self.medico.id)["id"], "k")
        notificaciones.enviar_pendientes()  # falla: proveedor caído
        proveedor = ProveedorFalso()
        try:
            with override_settings(PROVEEDOR_MENSAJERIA_URL=proveedor.url):
                self.assertEqual(notificaciones.enviar_pendientes()["enviados"], 1)
        finally:
            proveedor.cerrar()
        self.assertEqual(Envio.objects.get(cita_id=cita["id"]).estado, nd.ENVIADO)
        self.assertEqual(proveedor.recibidos[0]["destino"], "paciente@clinica.test")

    def test_el_proveedor_nunca_recibe_datos_clinicos(self):
        cita, _ = agenda.reservar(self.paciente.id, cupo(self.medico.id)["id"], "k")
        clinica.registrar_atencion(self.medico, self.paciente.id, cita["id"], "DIAGNOSTICO-SECRETO", "RECETA-SECRETA")
        agenda.cancelar(cita["id"], paciente_id=self.paciente.id)
        proveedor = ProveedorFalso()
        try:
            with override_settings(PROVEEDOR_MENSAJERIA_URL=proveedor.url):
                notificaciones.enviar_pendientes()
        finally:
            proveedor.cerrar()
        self.assertEqual(len(proveedor.recibidos), 2)  # confirmación y cancelación
        enviado = json.dumps(proveedor.recibidos)
        self.assertNotIn("SECRETO", enviado)
        self.assertNotIn("SECRETA", enviado)

    @override_settings(NOTIFICACIONES_CANAL="sms")
    def test_el_canal_se_cambia_por_configuracion_sin_tocar_agenda(self):
        cita, _ = agenda.reservar(self.paciente.id, cupo(self.medico.id)["id"], "k")
        envio = Envio.objects.get(cita_id=cita["id"])
        self.assertEqual((envio.canal, envio.destino), ("sms", "3000000000"))
