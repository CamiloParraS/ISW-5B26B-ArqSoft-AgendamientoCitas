"""Interfaz pública de Identidad y acceso.

Es lo ÚNICO que los demás módulos pueden importar de Identidad (lo verifica tests/test_fronteras.py).
Todos los módulos verifican la sesión aquí.
"""
from functools import wraps

from django.http import JsonResponse

from . import aplicacion
from .dominio import MEDICO, PACIENTE, RECEPCION, Contacto, CredencialesInvalidas, UsuarioActual  # noqa: F401

crear_usuario = aplicacion.crear_usuario
hay_usuarios = aplicacion.hay_usuarios
obtener = aplicacion.obtener
buscar = aplicacion.buscar
nombres = aplicacion.nombres
contacto = aplicacion.contacto
listar = aplicacion.listar


def token_de(request):
    cabecera = request.headers.get("Authorization", "")
    return cabecera[7:] if cabecera.startswith("Bearer ") else None


def usuario_de(request):
    return aplicacion.verificar_sesion(token_de(request))


def requiere_sesion(*roles):
    """Decorador de vistas: exige sesión válida y, si se indican, uno de los roles."""

    def decorador(vista):
        @wraps(vista)
        def envuelta(request, *args, **kwargs):
            usuario = usuario_de(request)
            if usuario is None:
                return JsonResponse({"error": "Sesión inválida o vencida"}, status=401)
            if roles and usuario.rol not in roles:
                return JsonResponse({"error": "No autorizado"}, status=403)
            request.usuario = usuario
            return vista(request, *args, **kwargs)

        return envuelta

    return decorador
