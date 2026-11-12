"""Muestra la bitácora de accesos clínicos: quién, qué y cuándo (escenario E-2)."""
from django.core.management.base import BaseCommand
from django.utils import timezone

from atencion_clinica import aplicacion


class Command(BaseCommand):
    help = "Muestra los últimos accesos a la historia clínica"

    def add_arguments(self, parser):
        parser.add_argument("--limite", type=int, default=20)

    def handle(self, *args, **opciones):
        filas = aplicacion.bitacora(opciones["limite"])
        if not filas:
            self.stdout.write("La bitácora está vacía.")
        for b in filas:
            self.stdout.write(
                f"{timezone.localtime(b.momento):%Y-%m-%d %H:%M:%S}  usuario={b.usuario_id:<3} rol={b.rol:<10} "
                f"{b.accion:<18} paciente={b.paciente_id:<3} {b.resultado:<9} {b.motivo}"
            )
