"""Interfaz pública de Atención clínica.

Ningún otro módulo lee datos clínicos: esta interfaz no expone diagnósticos ni recetas hacia otros módulos.
"""
from .dominio import AccesoDenegado  # noqa: F401
