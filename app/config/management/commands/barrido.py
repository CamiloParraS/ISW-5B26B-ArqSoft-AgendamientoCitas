"""Comando que ejecuta la Tarea programada cada 5 min: barrido de vencimientos + reintento de envíos."""
from django.core.management.base import BaseCommand
from django.utils import timezone

from agenda import api as agenda
from notificaciones import api as notificaciones


class Command(BaseCommand):
    help = "Barrido de vencimientos de Agenda (inasistencias y recordatorios) y reintento de notificaciones"

    def handle(self, *args, **opciones):
        resultado = agenda.barrer_vencimientos()
        envios = notificaciones.enviar_pendientes()
        self.stdout.write(
            f"[barrido {timezone.localtime():%Y-%m-%d %H:%M:%S}] "
            f"inasistencias={resultado['inasistencias']} recordatorios={resultado['recordatorios']} "
            f"enviados={envios['enviados']} pendientes={envios['pendientes']}"
        )
