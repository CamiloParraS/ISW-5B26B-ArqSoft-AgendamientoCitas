"""E-2 · Lectura clínica sin relación de tratamiento (S-1) y Recepción sin datos clínicos (S-2). ADR-003."""
from django.db import DatabaseError, connection, transaction
from django.test import TestCase

from agenda import aplicacion as agenda
from atencion_clinica import aplicacion as clinica
from atencion_clinica import dominio as d
from atencion_clinica.models import BitacoraAcceso, RegistroClinico
from identidad import api as identidad

from .utilidades import cupo, usuario


class HistoriaClinica(TestCase):
    def setUp(self):
        self.tratante = usuario("tratante", identidad.MEDICO)
        self.ajeno = usuario("ajeno", identidad.MEDICO)
        self.recepcion = usuario("recepcion", identidad.RECEPCION)
        self.paciente = usuario("paciente", identidad.PACIENTE)
        self.cita, _ = agenda.reservar(self.paciente.id, cupo(self.tratante.id)["id"], "k")  # evento CitaReservada

    def test_el_evento_cita_reservada_crea_la_relacion_y_el_tratante_lee(self):
        clinica.registrar_atencion(self.tratante, self.paciente.id, self.cita["id"], "Faringitis aguda", "Ibuprofeno 400 mg")
        historia = clinica.leer_historia(self.tratante, self.paciente.id)
        self.assertEqual(historia[0]["diagnostico"], "Faringitis aguda")
        self.assertEqual(BitacoraAcceso.objects.filter(usuario_id=self.tratante.id, resultado=d.PERMITIDO).count(), 2)

    def test_medico_sin_relacion_es_denegado_y_queda_en_la_bitacora(self):
        for _ in range(5):
            with self.assertRaises(d.AccesoDenegado):
                clinica.leer_historia(self.ajeno, self.paciente.id)
        intentos = BitacoraAcceso.objects.filter(usuario_id=self.ajeno.id)
        self.assertEqual(intentos.count(), 5)  # 100 % de los intentos registrados
        self.assertTrue(all(b.resultado == d.DENEGADO and b.accion == d.LEER_HISTORIA for b in intentos))

    def test_recepcion_no_ve_diagnosticos_ni_recetas(self):
        with self.assertRaises(d.AccesoDenegado):
            clinica.leer_historia(self.recepcion, self.paciente.id)
        with self.assertRaises(d.AccesoDenegado):
            clinica.registrar_atencion(self.recepcion, self.paciente.id, self.cita["id"], "x", "y")
        self.assertEqual(BitacoraAcceso.objects.filter(usuario_id=self.recepcion.id, resultado=d.DENEGADO).count(), 2)

    def test_la_denegacion_no_revela_si_hay_registros(self):
        sin_registros = usuario("sin_registros", identidad.PACIENTE)
        clinica.registrar_atencion(self.tratante, self.paciente.id, self.cita["id"], "Dx", "Rx")
        token, _ = identidad_sesion(self, "ajeno")
        a = self.client.get(f"/api/clinica/pacientes/{self.paciente.id}/historia", HTTP_AUTHORIZATION=f"Bearer {token}")
        b = self.client.get(f"/api/clinica/pacientes/{sin_registros.id}/historia", HTTP_AUTHORIZATION=f"Bearer {token}")
        self.assertEqual((a.status_code, a.json()), (b.status_code, b.json()))
        self.assertEqual(a.status_code, 403)

    def test_la_bitacora_es_de_solo_insercion(self):
        with self.assertRaises(d.AccesoDenegado):
            clinica.leer_historia(self.ajeno, self.paciente.id)
        for sql in ("UPDATE clinico.bitacora_acceso SET resultado = 'PERMITIDO'", "DELETE FROM clinico.bitacora_acceso"):
            with self.assertRaises(DatabaseError), transaction.atomic(), connection.cursor() as c:
                c.execute(sql)
        self.assertEqual(BitacoraAcceso.objects.get().resultado, d.DENEGADO)

    def test_los_registros_clinicos_no_se_borran_se_versionan(self):
        clinica.registrar_atencion(self.tratante, self.paciente.id, self.cita["id"], "Dx inicial", "Rx")
        clinica.registrar_atencion(self.tratante, self.paciente.id, self.cita["id"], "Dx corregido", "Rx")
        self.assertEqual(RegistroClinico.objects.filter(cita_id=self.cita["id"]).count(), 2)
        historia = clinica.leer_historia(self.tratante, self.paciente.id)
        self.assertEqual([(h["diagnostico"], h["version"]) for h in historia], [("Dx corregido", 2)])
        with self.assertRaises(DatabaseError), transaction.atomic(), connection.cursor() as c:
            c.execute("DELETE FROM clinico.registro_clinico")

    def test_diagnostico_y_receta_estan_cifrados_en_reposo(self):
        clinica.registrar_atencion(self.tratante, self.paciente.id, self.cita["id"], "Hipertensión", "Losartán 50 mg")
        with connection.cursor() as c:
            c.execute("SELECT diagnostico_cifrado, receta_cifrada FROM clinico.registro_clinico")
            diagnostico, receta = c.fetchone()
        self.assertNotIn("Hipertensión", diagnostico)
        self.assertNotIn("Losartán", receta)


def identidad_sesion(prueba, nombre):
    r = prueba.client.post("/api/identidad/sesiones", {"usuario": nombre, "clave": "clave1234"}, content_type="application/json")
    return r.json()["token"], r.json()["usuario"]
