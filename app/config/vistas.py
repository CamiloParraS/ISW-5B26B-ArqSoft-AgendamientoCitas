"""Ruta /salud (táctica "Latido y reinicio", E-3) y alerta del barrido atrasado (riesgo RS-3)."""
import logging
from datetime import timedelta

from django.conf import settings
from django.db import connection
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.http import require_GET

from agenda import api as agenda

log = logging.getLogger("salud")


@require_GET
def salud(request):
    try:
        with connection.cursor() as c:
            c.execute("SELECT 1")
        ultimo = agenda.ultimo_barrido()
    except Exception:  # noqa: BLE001
        return JsonResponse({"estado": "falla", "base_datos": "sin conexión"}, status=503)

    ahora = timezone.now()
    atrasado = ultimo is None or ahora - ultimo > timedelta(minutes=settings.BARRIDO_ALERTA_MINUTOS)
    if atrasado:
        log.warning("ALERTA RS-3: el último barrido tiene más de %s min (%s)", settings.BARRIDO_ALERTA_MINUTOS, ultimo)
    # El barrido atrasado NO hace fallar /salud: reiniciar la aplicación no arregla la tarea programada.
    return JsonResponse(
        {
            "estado": "ok",
            "base_datos": "ok",
            "ultimo_barrido": timezone.localtime(ultimo).isoformat() if ultimo else None,
            "barrido_atrasado": atrasado,
            "minutos_desde_barrido": round((ahora - ultimo).total_seconds() / 60, 1) if ultimo else None,
        }
    )
