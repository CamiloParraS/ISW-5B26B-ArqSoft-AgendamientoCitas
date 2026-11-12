"""Presentación HTTP/JSON de Atención clínica.

Cualquier denegación responde igual ("No autorizado"), sin revelar si el paciente tiene registros.
"""
import json

from django.http import JsonResponse
from django.views.decorators.http import require_GET, require_POST

from identidad import api as identidad

from . import aplicacion
from . import dominio as d

NO_AUTORIZADO = {"error": "No autorizado"}


@require_GET
@identidad.requiere_sesion()  # el rol se verifica en la aplicación para que el intento quede en la bitácora
def historia(request, paciente_id):
    try:
        registros = aplicacion.leer_historia(request.usuario, paciente_id)
    except d.AccesoDenegado:
        return JsonResponse(NO_AUTORIZADO, status=403)
    return JsonResponse({"paciente_id": paciente_id, "registros": registros})


@require_POST
@identidad.requiere_sesion()
def atenciones(request, paciente_id):
    try:
        c = json.loads(request.body or b"{}")
        registro = aplicacion.registrar_atencion(request.usuario, paciente_id, int(c["cita_id"]), c.get("diagnostico", ""), c.get("receta", ""))
    except d.AccesoDenegado:
        return JsonResponse(NO_AUTORIZADO, status=403)
    except d.DatosInvalidos as e:
        return JsonResponse({"error": str(e)}, status=422)
    except (json.JSONDecodeError, KeyError, TypeError, ValueError):
        return JsonResponse({"error": "Indique cita_id, diagnostico y receta"}, status=400)
    return JsonResponse({"registro": registro}, status=201)
