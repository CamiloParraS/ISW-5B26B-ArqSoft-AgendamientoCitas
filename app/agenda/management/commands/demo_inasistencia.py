"""Prepara el escenario E-4: una cita confirmada que empezó hace 16 min y nadie marcó asistencia."""
import uuid
from datetime import timedelta

from django.core.management.base import BaseCommand
from django.utils import timezone

from agenda import aplicacion
from identidad import api as identidad


class Command(BaseCommand):
    help = "Crea una cita confirmada que empezó hace 16 min (escenario E-4)"

    def handle(self, *args, **opciones):
        medico = identidad.buscar("dra.rojas")
        paciente = identidad.buscar("bruno")
        inicio = (timezone.now() - timedelta(minutes=16)).replace(second=0, microsecond=0)
        cupo = aplicacion.crear_cupo(medico.id, inicio, inicio + timedelta(minutes=20))
        cita, _ = aplicacion.reservar(paciente.id, cupo["id"], f"demo-e4-{uuid.uuid4()}", permitir_pasado=True)
        self.stdout.write(
            f"Cita #{cita['id']} de {paciente.nombre} con {medico.nombre}, inicio {timezone.localtime(inicio):%H:%M}, "
            f"estado {cita['estado']}.\nAhora corra el barrido (dos veces para ver que no duplica):\n"
            "  docker compose exec tarea-programada python manage.py barrido"
        )
