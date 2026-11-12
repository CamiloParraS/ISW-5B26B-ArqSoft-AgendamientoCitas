"""Infraestructura: cifrado en reposo de diagnóstico y receta (táctica "Cifrar", E-2).

Costo aceptado: no se puede buscar por texto dentro del diagnóstico.
"""
from functools import lru_cache

from cryptography.fernet import Fernet
from django.conf import settings


@lru_cache(maxsize=1)
def _fernet():
    return Fernet(settings.CLAVE_CIFRADO_CLINICO.encode())


def cifrar(texto):
    return _fernet().encrypt(texto.encode("utf-8")).decode("ascii")


def descifrar(cifrado):
    return _fernet().decrypt(cifrado.encode("ascii")).decode("utf-8")
