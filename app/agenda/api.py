"""Interfaz pública de Agenda. Lo ÚNICO que los demás módulos pueden importar de Agenda.

Atención clínica y Notificaciones se suscriben a los eventos de cita con `suscribir`.
"""
from . import aplicacion
from .dominio import (  # noqa: F401
    CitaCancelada,
    CitaNoEncontrada,
    CitaReservada,
    CupoInexistente,
    CupoYaTomado,
    ErrorDeAgenda,
    InasistenciaRegistrada,
    RecordatorioDebido,
    ReglaIncumplida,
)

suscribir = aplicacion.suscribir
crear_horario = aplicacion.crear_horario
cupos_disponibles = aplicacion.cupos_disponibles
reservar = aplicacion.reservar
cancelar = aplicacion.cancelar
barrer_vencimientos = aplicacion.barrer_vencimientos
ultimo_barrido = aplicacion.ultimo_barrido
