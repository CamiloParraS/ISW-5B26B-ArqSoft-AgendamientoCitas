"""Infraestructura de Notificaciones: esquema "notificaciones"."""
from django.db import models

from .dominio import ESTADOS, PENDIENTE, TIPOS


class Envio(models.Model):
    tipo = models.CharField(max_length=20, choices=[(t, t) for t in TIPOS])
    cita_id = models.BigIntegerField()
    paciente_id = models.BigIntegerField()
    canal = models.CharField(max_length=20)
    destino = models.CharField(max_length=254)
    mensaje = models.TextField()
    estado = models.CharField(max_length=10, choices=[(e, e) for e in ESTADOS], default=PENDIENTE)
    intentos = models.PositiveIntegerField(default=0)
    ultimo_error = models.CharField(max_length=500, blank=True)
    creado = models.DateTimeField(auto_now_add=True)
    enviado_en = models.DateTimeField(null=True)

    class Meta:
        db_table = 'notificaciones"."envio'
        # Un solo envío por tipo y cita: aunque el barrido corra dos veces, no hay duplicados.
        constraints = [models.UniqueConstraint(fields=["tipo", "cita_id"], name="un_envio_por_tipo_y_cita")]
        indexes = [models.Index(fields=["estado", "id"], name="envio_pendientes_idx")]
