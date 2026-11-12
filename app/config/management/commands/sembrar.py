"""Datos de demostración. Idempotente: se ejecuta en cada arranque."""
from datetime import time, timedelta

from django.core.management.base import BaseCommand
from django.utils import timezone

from agenda import api as agenda
from identidad import api as identidad

CLAVE_DEMO = "demo1234"
USUARIOS = [
    ("ana", "Ana Gómez", identidad.PACIENTE, "ana@clinica.test", "3001112233"),
    ("bruno", "Bruno Díaz", identidad.PACIENTE, "bruno@clinica.test", "3004445566"),
    ("dra.rojas", "Dra. Laura Rojas", identidad.MEDICO, "lrojas@clinica.test", "3007778899"),
    ("dr.mora", "Dr. Andrés Mora", identidad.MEDICO, "amora@clinica.test", "3001231234"),
    ("recepcion", "Recepción", identidad.RECEPCION, "recepcion@clinica.test", "3009876543"),
]


class Command(BaseCommand):
    help = "Crea usuarios de demostración y abre cupos para los próximos 5 días hábiles"

    def handle(self, *args, **opciones):
        primera_vez = not identidad.hay_usuarios()
        for usuario, nombre, rol, correo, telefono in USUARIOS:
            identidad.crear_usuario(usuario, CLAVE_DEMO, nombre, rol, correo, telefono)

        medicos = [identidad.buscar("dra.rojas"), identidad.buscar("dr.mora")]
        dia = timezone.localdate()
        abiertos = 0
        while abiertos < 5:
            dia += timedelta(days=1)
            if dia.weekday() < 5:
                for medico in medicos:
                    agenda.crear_horario(medico.id, dia, time(8, 0), time(12, 0), 20)
                abiertos += 1

        if primera_vez:
            # Ana ya tiene cita con la Dra. Rojas: existe relación de tratamiento entre ellas (y no con el Dr. Mora).
            ana = identidad.buscar("ana")
            cupo = agenda.cupos_disponibles(medicos[0].id)[0]
            agenda.reservar(ana.id, cupo["id"], "semilla-ana")
        self.stdout.write(f"Semilla lista ({'creada' if primera_vez else 'ya existía'}). Clave de todos los usuarios: {CLAVE_DEMO}")
