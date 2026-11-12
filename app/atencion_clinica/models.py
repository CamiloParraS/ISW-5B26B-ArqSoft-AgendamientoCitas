"""Infraestructura de Atención clínica: esquema "clinico" que ningún otro módulo lee.

- RelacionTratamiento: copia propia construida desde el evento CitaReservada (no se le pregunta a Agenda).
- RegistroClinico: diagnóstico y receta cifrados; no se borran, se versionan (R-3).
- BitacoraAcceso: solo inserción; un disparador en la base rechaza UPDATE y DELETE.
"""
from django.db import models

from .dominio import DENEGADO, LEER_HISTORIA, PERMITIDO, REGISTRAR_ATENCION


class RelacionTratamiento(models.Model):
    medico_id = models.BigIntegerField()
    paciente_id = models.BigIntegerField()
    cita_origen_id = models.BigIntegerField()
    desde = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'clinico"."relacion_tratamiento'
        constraints = [models.UniqueConstraint(fields=["medico_id", "paciente_id"], name="una_relacion_por_medico_y_paciente")]


class RegistroClinico(models.Model):
    paciente_id = models.BigIntegerField()
    medico_id = models.BigIntegerField()
    cita_id = models.BigIntegerField()
    version = models.PositiveIntegerField()
    diagnostico_cifrado = models.TextField()
    receta_cifrada = models.TextField()
    creado = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'clinico"."registro_clinico'
        constraints = [models.UniqueConstraint(fields=["cita_id", "version"], name="una_version_por_cita")]
        indexes = [models.Index(fields=["paciente_id"], name="registro_paciente_idx")]


class BitacoraAcceso(models.Model):
    usuario_id = models.BigIntegerField()
    rol = models.CharField(max_length=20)
    accion = models.CharField(max_length=30, choices=[(a, a) for a in (LEER_HISTORIA, REGISTRAR_ATENCION)])
    paciente_id = models.BigIntegerField()
    resultado = models.CharField(max_length=10, choices=[(r, r) for r in (PERMITIDO, DENEGADO)])
    motivo = models.CharField(max_length=200)
    momento = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'clinico"."bitacora_acceso'
        indexes = [models.Index(fields=["paciente_id", "momento"], name="bitacora_paciente_idx")]
