"""Infraestructura de Identidad: tablas propias en el esquema "identidad"."""
from django.db import models

from .dominio import ROLES


class Usuario(models.Model):
    usuario = models.CharField(max_length=50, unique=True)
    nombre = models.CharField(max_length=120)
    rol = models.CharField(max_length=20, choices=[(r, r) for r in ROLES])
    clave_hash = models.CharField(max_length=256)
    correo = models.CharField(max_length=254, blank=True)
    telefono = models.CharField(max_length=30, blank=True)

    class Meta:
        db_table = 'identidad"."usuario'


class Sesion(models.Model):
    token_hash = models.CharField(max_length=64, unique=True)
    usuario = models.ForeignKey(Usuario, on_delete=models.CASCADE, related_name="sesiones")
    creada = models.DateTimeField(auto_now_add=True)
    expira = models.DateTimeField()

    class Meta:
        db_table = 'identidad"."sesion'
