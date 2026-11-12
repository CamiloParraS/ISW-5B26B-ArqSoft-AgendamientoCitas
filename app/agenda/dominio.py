"""Dominio de Agenda: cupos, citas y reglas de 24 h y 15 min. Reglas puras, sin Django ni base de datos.

"Paciente" en Agenda = quien ocupa un cupo.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta

# Estados del cupo
LIBRE = "LIBRE"
TOMADO = "TOMADO"
ESTADOS_CUPO = (LIBRE, TOMADO)

# Estados de la cita
CONFIRMADA = "CONFIRMADA"
CANCELADA = "CANCELADA"
ATENDIDA = "ATENDIDA"
INASISTENCIA = "INASISTENCIA"
ESTADOS_CITA = (CONFIRMADA, CANCELADA, ATENDIDA, INASISTENCIA)

# Regla de 15 min: sin asistencia marcada 15 min después del inicio -> inasistencia (T-1)
MINUTOS_PARA_INASISTENCIA = 15
# Regla de 24 h: el recordatorio sale 24 h antes, ±30 min (T-2)
ANTICIPACION_RECORDATORIO = timedelta(hours=24)
TOLERANCIA_RECORDATORIO = timedelta(minutes=30)


# --- Eventos de cita (los consumen Atención clínica y Notificaciones) ---
@dataclass(frozen=True)
class CitaReservada:
    cita_id: int
    paciente_id: int
    medico_id: int
    inicio: datetime


@dataclass(frozen=True)
class CitaCancelada:
    cita_id: int
    paciente_id: int
    medico_id: int
    inicio: datetime


@dataclass(frozen=True)
class InasistenciaRegistrada:
    cita_id: int
    paciente_id: int
    medico_id: int
    inicio: datetime


@dataclass(frozen=True)
class RecordatorioDebido:
    cita_id: int
    paciente_id: int
    medico_id: int
    inicio: datetime


# --- Errores de dominio ---
class ErrorDeAgenda(Exception):
    pass


class CupoYaTomado(ErrorDeAgenda):
    def __init__(self, alternativas):
        super().__init__("Cupo ya tomado")
        self.alternativas = alternativas


class CupoInexistente(ErrorDeAgenda):
    pass


class CitaNoEncontrada(ErrorDeAgenda):
    pass


class ReglaIncumplida(ErrorDeAgenda):
    pass


# --- Reglas ---
def generar_franjas(fecha, hora_inicio, hora_fin, duracion_minutos, zona):
    """Divide un horario de atención en cupos consecutivos de igual duración."""
    if not 5 <= duracion_minutos <= 240:
        raise ReglaIncumplida("La duración del cupo debe estar entre 5 y 240 minutos")
    inicio = datetime.combine(fecha, hora_inicio, tzinfo=zona)
    fin = datetime.combine(fecha, hora_fin, tzinfo=zona)
    if fin <= inicio:
        raise ReglaIncumplida("La hora final debe ser posterior a la hora inicial")
    paso = timedelta(minutes=duracion_minutos)
    franjas = []
    t = inicio
    while t + paso <= fin:
        franjas.append((t, t + paso))
        t += paso
    return franjas


def limite_inasistencia(ahora):
    """Citas que empezaron antes de este instante y siguen confirmadas son inasistencias."""
    return ahora - timedelta(minutes=MINUTOS_PARA_INASISTENCIA)


def ventana_recordatorio(ahora):
    """Citas cuyo inicio cae en esta ventana deben recibir recordatorio ahora (24 h antes, hasta 30 min tarde)."""
    hasta = ahora + ANTICIPACION_RECORDATORIO
    return hasta - TOLERANCIA_RECORDATORIO, hasta
