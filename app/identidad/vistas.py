"""Presentación HTTP/JSON de Identidad."""
import json

from django.http import JsonResponse
from django.views.decorators.http import require_GET, require_http_methods

from . import aplicacion
from .api import requiere_sesion, token_de
from .dominio import MEDICO, PACIENTE, RECEPCION, CredencialesInvalidas


def _usuario_json(u):
    return {"id": u.id, "usuario": u.usuario, "nombre": u.nombre, "rol": u.rol}


@require_http_methods(["POST", "DELETE"])
def sesiones(request):
    if request.method == "DELETE":
        aplicacion.cerrar_sesion(token_de(request))
        return JsonResponse({}, status=200)
    try:
        cuerpo = json.loads(request.body or b"{}")
        token, usuario = aplicacion.iniciar_sesion(cuerpo.get("usuario", ""), cuerpo.get("clave", ""))
    except (json.JSONDecodeError, CredencialesInvalidas):
        return JsonResponse({"error": "Usuario o clave incorrectos"}, status=401)
    return JsonResponse({"token": token, "usuario": _usuario_json(usuario)}, status=201)


@require_GET
@requiere_sesion()
def yo(request):
    return JsonResponse(_usuario_json(request.usuario))


@require_GET
@requiere_sesion()
def medicos(request):
    return JsonResponse({"medicos": [_usuario_json(u) for u in aplicacion.listar(MEDICO)]})


@require_GET
@requiere_sesion(MEDICO, RECEPCION)
def pacientes(request):
    return JsonResponse({"pacientes": [_usuario_json(u) for u in aplicacion.listar(PACIENTE)]})
