"""Presentación HTTP/JSON de Notificaciones."""
from django.http import JsonResponse
from django.views.decorators.http import require_GET

from identidad import api as identidad

from . import aplicacion


@require_GET
@identidad.requiere_sesion(identidad.PACIENTE)
def mias(request):
    return JsonResponse({"envios": aplicacion.del_paciente(request.usuario.id)})
