"""Dominio de Identidad y acceso: usuarios, roles y sesiones. Sin Django."""
from dataclasses import dataclass

PACIENTE = "PACIENTE"
MEDICO = "MEDICO"
RECEPCION = "RECEPCION"
ROLES = (PACIENTE, MEDICO, RECEPCION)


@dataclass(frozen=True)
class UsuarioActual:
    id: int
    usuario: str
    nombre: str
    rol: str


@dataclass(frozen=True)
class Contacto:
    correo: str
    telefono: str


class CredencialesInvalidas(Exception):
    pass
