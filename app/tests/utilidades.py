from datetime import timedelta

from django.apps import apps
from django.db import connection
from django.test import TransactionTestCase
from django.utils import timezone

from agenda import aplicacion as agenda
from identidad import api as identidad


def usuario(nombre, rol):
    return identidad.crear_usuario(nombre, "clave1234", nombre.title(), rol, f"{nombre}@clinica.test", "3000000000")


def cupo(medico_id, en=timedelta(days=2), minutos=20):
    inicio = (timezone.now() + en).replace(second=0, microsecond=0)
    return agenda.crear_cupo(medico_id, inicio, inicio + timedelta(minutes=minutos))


class PruebaConHilos(TransactionTestCase):
    """TransactionTestCase que también vacía las tablas de los esquemas de cada módulo.

    El flush de Django solo ve las tablas del esquema public; las nuestras viven en agenda, clinico, etc.
    """

    def _fixture_teardown(self):
        super()._fixture_teardown()
        tablas = ", ".join(f'"{m._meta.db_table}"' for m in apps.get_models())
        with connection.cursor() as c:
            c.execute(f"TRUNCATE {tablas} RESTART IDENTITY CASCADE")
