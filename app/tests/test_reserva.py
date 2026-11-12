"""E-1 · Reservas simultáneas sobre un cupo (C-1) y doble clic / reintento (C-2). ADR-002."""
import threading
import uuid
from datetime import timedelta

from django.db import IntegrityError, connection, transaction
from django.test import TestCase

from agenda import aplicacion as agenda
from agenda import dominio as d
from agenda.models import Cita
from identidad import api as identidad

from .utilidades import PruebaConHilos, cupo, usuario


def _en_paralelo(n, funcion):
    """Lanza n hilos que arrancan a la vez (barrera) y devuelve sus resultados."""
    barrera = threading.Barrier(n)
    resultados = [None] * n

    def correr(i):
        try:
            barrera.wait()
            resultados[i] = funcion(i)
        except Exception as e:  # noqa: BLE001
            resultados[i] = e
        finally:
            connection.close()

    hilos = [threading.Thread(target=correr, args=(i,)) for i in range(n)]
    for h in hilos:
        h.start()
    for h in hilos:
        h.join()
    return resultados


class ReservasSimultaneas(PruebaConHilos):
    SOLICITUDES = 30
    REPETICIONES = 10  # el script escenarios/e1_reservas_simultaneas.py hace las 100 contra el sistema desplegado

    def test_30_solicitudes_sobre_el_mismo_cupo_confirman_exactamente_una(self):
        medico = usuario("medico", identidad.MEDICO)
        pacientes = [usuario(f"paciente{i}", identidad.PACIENTE) for i in range(self.SOLICITUDES)]
        for repeticion in range(self.REPETICIONES):
            c = cupo(medico.id, en=timedelta(days=2, hours=repeticion))
            cupo(medico.id, en=timedelta(days=3, hours=repeticion))  # una alternativa libre

            resultados = _en_paralelo(
                self.SOLICITUDES, lambda i: agenda.reservar(pacientes[i].id, c["id"], uuid.uuid4().hex)
            )

            confirmadas = [r for r in resultados if isinstance(r, tuple)]
            rechazadas = [r for r in resultados if isinstance(r, d.CupoYaTomado)]
            self.assertEqual(len(confirmadas), 1, f"repetición {repeticion}: {resultados}")
            self.assertEqual(len(rechazadas), self.SOLICITUDES - 1)
            self.assertTrue(all(r.alternativas for r in rechazadas), "los rechazados reciben alternativas")
            self.assertEqual(Cita.objects.filter(cupo_id=c["id"]).exclude(estado=d.CANCELADA).count(), 1)

    def test_doble_clic_concurrente_con_la_misma_clave_crea_una_sola_cita(self):
        medico = usuario("medico", identidad.MEDICO)
        paciente = usuario("paciente", identidad.PACIENTE)
        c = cupo(medico.id)
        resultados = _en_paralelo(2, lambda i: agenda.reservar(paciente.id, c["id"], "clic-1"))
        self.assertTrue(all(isinstance(r, tuple) for r in resultados), resultados)
        self.assertEqual(len({r[0]["id"] for r in resultados}), 1)
        self.assertEqual(sorted(r[1] for r in resultados), [False, True])
        self.assertEqual(Cita.objects.count(), 1)


class Reserva(TestCase):
    def setUp(self):
        self.medico = usuario("medico", identidad.MEDICO)
        self.paciente = usuario("paciente", identidad.PACIENTE)
        self.cupo = cupo(self.medico.id)

    def test_reintento_con_la_misma_clave_devuelve_la_misma_cita(self):
        primera, creada1 = agenda.reservar(self.paciente.id, self.cupo["id"], "clave-1")
        segunda, creada2 = agenda.reservar(self.paciente.id, self.cupo["id"], "clave-1")
        self.assertEqual(primera["id"], segunda["id"])
        self.assertEqual((creada1, creada2), (True, False))
        self.assertEqual(Cita.objects.count(), 1)

    def test_cupo_tomado_responde_con_alternativas(self):
        otro = usuario("otro", identidad.PACIENTE)
        alternativa = cupo(self.medico.id, en=timedelta(days=3))
        agenda.reservar(self.paciente.id, self.cupo["id"], "a")
        with self.assertRaises(d.CupoYaTomado) as ctx:
            agenda.reservar(otro.id, self.cupo["id"], "b")
        self.assertEqual([a["id"] for a in ctx.exception.alternativas], [alternativa["id"]])

    def test_el_indice_unico_impide_dos_citas_vigentes_en_un_cupo(self):
        cita, _ = agenda.reservar(self.paciente.id, self.cupo["id"], "a")
        original = Cita.objects.get(id=cita["id"])
        with self.assertRaises(IntegrityError), transaction.atomic():
            Cita.objects.create(
                cupo_id=self.cupo["id"], paciente_id=999, medico_id=self.medico.id, inicio=original.inicio, fin=original.fin
            )

    def test_cancelar_libera_el_cupo(self):
        cita, _ = agenda.reservar(self.paciente.id, self.cupo["id"], "a")
        agenda.cancelar(cita["id"], paciente_id=self.paciente.id)
        nueva, creada = agenda.reservar(self.paciente.id, self.cupo["id"], "b")
        self.assertTrue(creada)
        self.assertNotEqual(nueva["id"], cita["id"])

    def test_reprogramar_toma_el_nuevo_cupo_y_libera_el_anterior(self):
        otro_cupo = cupo(self.medico.id, en=timedelta(days=4))
        cita, _ = agenda.reservar(self.paciente.id, self.cupo["id"], "a")
        nueva, _ = agenda.reprogramar(self.paciente.id, cita["id"], otro_cupo["id"], "r1")
        self.assertEqual(nueva["cupo_id"], otro_cupo["id"])
        self.assertEqual(Cita.objects.get(id=cita["id"]).estado, d.CANCELADA)
        self.assertIn(self.cupo["id"], [c["id"] for c in agenda.cupos_disponibles(self.medico.id)])
        # Reintento de la misma reprogramación: no hace nada nuevo.
        otra_vez, creada = agenda.reprogramar(self.paciente.id, cita["id"], otro_cupo["id"], "r1")
        self.assertEqual((otra_vez["id"], creada), (nueva["id"], False))

    def test_no_se_reserva_un_cupo_que_ya_paso(self):
        pasado = cupo(self.medico.id, en=-timedelta(hours=1))
        with self.assertRaises(d.ReglaIncumplida):
            agenda.reservar(self.paciente.id, pasado["id"], "a")


class ReservaPorHttp(TestCase):
    def setUp(self):
        self.medico = usuario("medico", identidad.MEDICO)
        usuario("paciente", identidad.PACIENTE)
        self.cupo = cupo(self.medico.id)
        r = self.client.post("/api/identidad/sesiones", {"usuario": "paciente", "clave": "clave1234"}, content_type="application/json")
        self.auth = {"HTTP_AUTHORIZATION": f"Bearer {r.json()['token']}"}

    def _reservar(self, clave):
        cabeceras = dict(self.auth, HTTP_IDEMPOTENCY_KEY=clave) if clave else self.auth
        return self.client.post("/api/agenda/citas", {"cupo_id": self.cupo["id"]}, content_type="application/json", **cabeceras)

    def test_reserva_reintento_y_conflicto(self):
        self.assertEqual(self._reservar("k1").status_code, 201)
        self.assertEqual(self._reservar("k1").status_code, 200)  # reintento: misma cita
        conflicto = self._reservar("k2")
        self.assertEqual(conflicto.status_code, 409)
        self.assertEqual(conflicto.json()["error"], "Cupo ya tomado")
        self.assertIn("alternativas", conflicto.json())

    def test_sin_clave_de_idempotencia_se_rechaza(self):
        self.assertEqual(self._reservar(None).status_code, 400)

    def test_sin_sesion_se_rechaza(self):
        r = self.client.post("/api/agenda/citas", {"cupo_id": self.cupo["id"]}, content_type="application/json", HTTP_IDEMPOTENCY_KEY="x")
        self.assertEqual(r.status_code, 401)
