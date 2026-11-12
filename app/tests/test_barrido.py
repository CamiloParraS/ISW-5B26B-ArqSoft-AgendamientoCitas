"""E-4 · Inasistencia después de un reinicio (T-1) y recordatorio 24 h antes ±30 min (T-2)."""
from datetime import timedelta

from django.core.management import call_command
from django.test import TestCase
from django.utils import timezone

from agenda import aplicacion as agenda
from agenda import dominio as d
from agenda.models import Cita
from identidad import api as identidad
from notificaciones import dominio as nd
from notificaciones.models import Envio

from .utilidades import cupo, usuario


class BarridoDeVencimientos(TestCase):
    def setUp(self):
        self.medico = usuario("medico", identidad.MEDICO)
        self.paciente = usuario("paciente", identidad.PACIENTE)

    def _cita(self, en):
        c = cupo(self.medico.id, en=en)
        cita, _ = agenda.reservar(self.paciente.id, c["id"], f"k{c['id']}", permitir_pasado=True)
        return cita

    def test_inasistencia_se_marca_una_sola_vez_aunque_el_barrido_corra_dos_veces(self):
        cita = self._cita(en=-timedelta(minutes=16))
        primera = agenda.barrer_vencimientos()
        segunda = agenda.barrer_vencimientos()  # p. ej. el proceso se reinició y el barrido vuelve a correr
        self.assertEqual((primera["inasistencias"], segunda["inasistencias"]), (1, 0))
        self.assertEqual(Cita.objects.get(id=cita["id"]).estado, d.INASISTENCIA)
        # La notificación queda pendiente, una sola.
        self.assertEqual(Envio.objects.filter(tipo=nd.INASISTENCIA, cita_id=cita["id"], estado=nd.PENDIENTE).count(), 1)

    def test_a_los_14_minutos_todavia_no_es_inasistencia(self):
        cita = self._cita(en=-timedelta(minutes=14))
        self.assertEqual(agenda.barrer_vencimientos()["inasistencias"], 0)
        self.assertEqual(Cita.objects.get(id=cita["id"]).estado, d.CONFIRMADA)

    def test_con_barrido_cada_5_min_se_registra_a_mas_tardar_a_los_20_min(self):
        cita = self._cita(en=-timedelta(minutes=14))
        inicio = Cita.objects.get(id=cita["id"]).inicio
        ahora = inicio + timedelta(minutes=14)
        while Cita.objects.get(id=cita["id"]).estado == d.CONFIRMADA:
            ahora += timedelta(minutes=5)
            agenda.barrer_vencimientos(ahora)
        self.assertLessEqual(ahora - inicio, timedelta(minutes=20))

    def test_la_cita_atendida_no_se_marca_como_inasistencia(self):
        cita = self._cita(en=-timedelta(minutes=30))
        agenda.marcar_asistencia(self.medico.id, cita["id"])
        self.assertEqual(agenda.barrer_vencimientos()["inasistencias"], 0)

    def test_recordatorio_24_h_antes_una_sola_vez(self):
        cita = self._cita(en=timedelta(hours=23, minutes=58))
        lejana = self._cita(en=timedelta(hours=30))
        self.assertEqual(agenda.barrer_vencimientos()["recordatorios"], 1)
        self.assertEqual(agenda.barrer_vencimientos()["recordatorios"], 0)
        self.assertEqual(Envio.objects.filter(tipo=nd.RECORDATORIO, cita_id=cita["id"]).count(), 1)
        self.assertFalse(Envio.objects.filter(tipo=nd.RECORDATORIO, cita_id=lejana["id"]).exists())

    def test_el_comando_barrido_deja_constancia_para_la_alerta_rs3(self):
        self.assertIsNone(agenda.ultimo_barrido())
        call_command("barrido", stdout=open("/dev/null", "w"))
        self.assertLess(timezone.now() - agenda.ultimo_barrido(), timedelta(minutes=1))
        r = self.client.get("/salud")
        self.assertEqual(r.status_code, 200)
        self.assertFalse(r.json()["barrido_atrasado"])


class Salud(TestCase):
    def test_salud_alerta_si_no_hay_barrido_reciente_sin_fallar(self):
        r = self.client.get("/salud")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["base_datos"], "ok")
        self.assertTrue(r.json()["barrido_atrasado"])
