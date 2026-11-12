"""Infraestructura de Agenda: tablas propias en el esquema "agenda".

Otros módulos se refieren a pacientes y médicos por identificador (paciente_id, medico_id),
nunca con llaves foráneas a tablas de otro esquema.
"""
from django.db import connection, models
from django.db.models import Q

from .dominio import CANCELADA, CONFIRMADA, ESTADOS_CITA, ESTADOS_CUPO, INASISTENCIA, LIBRE


class Cupo(models.Model):
    medico_id = models.BigIntegerField()
    inicio = models.DateTimeField()
    fin = models.DateTimeField()
    estado = models.CharField(max_length=10, choices=[(e, e) for e in ESTADOS_CUPO], default=LIBRE)

    class Meta:
        db_table = 'agenda"."cupo'
        constraints = [models.UniqueConstraint(fields=["medico_id", "inicio"], name="cupo_unico_por_medico_e_inicio")]
        indexes = [models.Index(fields=["medico_id", "estado", "inicio"], name="cupo_disponibilidad_idx")]


class Cita(models.Model):
    cupo = models.ForeignKey(Cupo, on_delete=models.PROTECT, related_name="citas")
    paciente_id = models.BigIntegerField()
    medico_id = models.BigIntegerField()
    inicio = models.DateTimeField()
    fin = models.DateTimeField()
    estado = models.CharField(max_length=15, choices=[(e, e) for e in ESTADOS_CITA], default=CONFIRMADA)
    recordatorio_programado = models.BooleanField(default=False)
    creada = models.DateTimeField(auto_now_add=True)
    actualizada = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'agenda"."cita'
        constraints = [
            # Táctica "Índice único" (ADR-002): red de seguridad, una sola cita vigente por cupo.
            models.UniqueConstraint(fields=["cupo"], condition=~Q(estado=CANCELADA), name="una_cita_vigente_por_cupo"),
        ]
        indexes = [
            models.Index(fields=["estado", "inicio"], name="cita_estado_inicio_idx"),
            models.Index(fields=["paciente_id", "inicio"], name="cita_paciente_idx"),
            models.Index(fields=["medico_id", "inicio"], name="cita_medico_idx"),
        ]


class SolicitudReserva(models.Model):
    """Táctica "Idempotencia": clave única por solicitud; un reintento devuelve la misma cita."""

    paciente_id = models.BigIntegerField()
    clave = models.CharField(max_length=100)
    cita = models.ForeignKey(Cita, on_delete=models.CASCADE, null=True, related_name="+")
    creada = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'agenda"."solicitud_reserva'
        constraints = [models.UniqueConstraint(fields=["paciente_id", "clave"], name="una_reserva_por_clave")]


class EjecucionBarrido(models.Model):
    inicio = models.DateTimeField()
    fin = models.DateTimeField(null=True)
    inasistencias = models.IntegerField(default=0)
    recordatorios = models.IntegerField(default=0)

    class Meta:
        db_table = 'agenda"."ejecucion_barrido'


# --- Cambios de estado condicionales del barrido (una sola sentencia, idempotentes) ---

def marcar_inasistencias(limite, ahora):
    """CONFIRMADA -> INASISTENCIA solo si sigue confirmada: correr el barrido dos veces no duplica nada."""
    with connection.cursor() as c:
        c.execute(
            """
            UPDATE agenda.cita SET estado = %s, actualizada = %s
             WHERE estado = %s AND inicio <= %s
         RETURNING id, paciente_id, medico_id, inicio
            """,
            [INASISTENCIA, ahora, CONFIRMADA, limite],
        )
        return c.fetchall()


def marcar_recordatorios(desde, hasta, ahora):
    """Marca una sola vez las citas que deben recibir recordatorio."""
    with connection.cursor() as c:
        c.execute(
            """
            UPDATE agenda.cita SET recordatorio_programado = TRUE, actualizada = %s
             WHERE estado = %s AND NOT recordatorio_programado AND inicio BETWEEN %s AND %s
         RETURNING id, paciente_id, medico_id, inicio
            """,
            [ahora, CONFIRMADA, desde, hasta],
        )
        return c.fetchall()
