"""Presentación HTTP/JSON de Agenda."""
import json
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from identidad import api as identidad

from . import aplicacion
from . import dominio as d


def _cuerpo(request):
    try:
        return json.loads(request.body or b"{}")
    except json.JSONDecodeError:
        raise d.ReglaIncumplida("El cuerpo no es JSON válido")


def _dia(texto):
    """'2026-11-16' -> inicio de ese día en la zona de la clínica."""
    return datetime.combine(date.fromisoformat(texto), time.min, tzinfo=ZoneInfo(settings.TIME_ZONE))


def _error(e):
    if isinstance(e, d.CupoYaTomado):
        return JsonResponse({"error": "Cupo ya tomado", "alternativas": e.alternativas}, status=409)
    if isinstance(e, (d.CupoInexistente, d.CitaNoEncontrada)):
        return JsonResponse({"error": str(e)}, status=404)
    return JsonResponse({"error": str(e)}, status=422)


def _con_nombres(lista):
    nombres = identidad.nombres([c["medico_id"] for c in lista] + [c["paciente_id"] for c in lista])
    for c in lista:
        c["medico"] = nombres.get(c["medico_id"])
        c["paciente"] = nombres.get(c["paciente_id"])
    return lista


@require_GET
@identidad.requiere_sesion()
def cupos(request):
    try:
        medico_id = int(request.GET["medico_id"])
        desde = _dia(request.GET["desde"]) if request.GET.get("desde") else None
        hasta = _dia(request.GET["hasta"]) + timedelta(days=1) if request.GET.get("hasta") else None
    except (KeyError, ValueError):
        return JsonResponse({"error": "Indique medico_id y fechas válidas (AAAA-MM-DD)"}, status=400)
    return JsonResponse({"cupos": aplicacion.cupos_disponibles(medico_id, desde, hasta)})


@require_http_methods(["GET", "POST"])
@identidad.requiere_sesion()
def citas(request):
    u = request.usuario
    if request.method == "GET":
        try:
            desde = _dia(request.GET["desde"]) if request.GET.get("desde") else None
            hasta = _dia(request.GET["hasta"]) + timedelta(days=1) if request.GET.get("hasta") else None
            medico_filtro = int(request.GET["medico_id"]) if request.GET.get("medico_id") else None
        except ValueError:
            return JsonResponse({"error": "Parámetros inválidos"}, status=400)
        if u.rol == identidad.PACIENTE:
            lista = aplicacion.citas(paciente_id=u.id, desde=desde, hasta=hasta)
        elif u.rol == identidad.MEDICO:
            lista = aplicacion.citas(medico_id=u.id, desde=desde, hasta=hasta)
        else:
            lista = aplicacion.citas(medico_id=medico_filtro, desde=desde, hasta=hasta)
        return JsonResponse({"citas": _con_nombres(lista)})

    if u.rol != identidad.PACIENTE:
        return JsonResponse({"error": "No autorizado"}, status=403)
    clave = request.headers.get("Idempotency-Key", "").strip()
    if not clave:
        return JsonResponse({"error": "Falta la cabecera Idempotency-Key"}, status=400)
    try:
        cuerpo = _cuerpo(request)
        cita, creada = aplicacion.reservar(u.id, int(cuerpo.get("cupo_id", 0)), clave)
    except d.ErrorDeAgenda as e:
        return _error(e)
    except (TypeError, ValueError):
        return JsonResponse({"error": "cupo_id inválido"}, status=400)
    return JsonResponse({"cita": _con_nombres([cita])[0], "creada": creada}, status=201 if creada else 200)


@require_POST
@identidad.requiere_sesion(identidad.PACIENTE, identidad.RECEPCION)
def cancelar(request, cita_id):
    u = request.usuario
    try:
        cita = aplicacion.cancelar(cita_id, paciente_id=u.id if u.rol == identidad.PACIENTE else None)
    except d.ErrorDeAgenda as e:
        return _error(e)
    return JsonResponse({"cita": cita})


@require_POST
@identidad.requiere_sesion(identidad.PACIENTE)
def reprogramar(request, cita_id):
    clave = request.headers.get("Idempotency-Key", "").strip()
    if not clave:
        return JsonResponse({"error": "Falta la cabecera Idempotency-Key"}, status=400)
    try:
        cuerpo = _cuerpo(request)
        cita, creada = aplicacion.reprogramar(request.usuario.id, cita_id, int(cuerpo.get("cupo_id", 0)), clave)
    except d.ErrorDeAgenda as e:
        return _error(e)
    except (TypeError, ValueError):
        return JsonResponse({"error": "cupo_id inválido"}, status=400)
    return JsonResponse({"cita": _con_nombres([cita])[0], "creada": creada}, status=201 if creada else 200)


@require_POST
@identidad.requiere_sesion(identidad.MEDICO)
def asistencia(request, cita_id):
    try:
        cita = aplicacion.marcar_asistencia(request.usuario.id, cita_id)
    except d.ErrorDeAgenda as e:
        return _error(e)
    return JsonResponse({"cita": cita})


@require_POST
@identidad.requiere_sesion(identidad.RECEPCION)
def horarios(request):
    try:
        c = _cuerpo(request)
        resultado = aplicacion.crear_horario(
            int(c["medico_id"]),
            date.fromisoformat(c["fecha"]),
            time.fromisoformat(c["hora_inicio"]),
            time.fromisoformat(c["hora_fin"]),
            int(c.get("duracion_minutos", 20)),
        )
    except d.ErrorDeAgenda as e:
        return _error(e)
    except (KeyError, TypeError, ValueError):
        return JsonResponse({"error": "Indique medico_id, fecha, hora_inicio, hora_fin y duracion_minutos"}, status=400)
    return JsonResponse(resultado, status=201)


@require_GET
@identidad.requiere_sesion(identidad.RECEPCION)
def reporte_mensual(request):
    try:
        anio, mes = (int(x) for x in request.GET["mes"].split("-"))
    except (KeyError, ValueError):
        return JsonResponse({"error": "Indique mes=AAAA-MM"}, status=400)
    return JsonResponse({"mes": f"{anio:04d}-{mes:02d}", "filas": aplicacion.reporte_mensual(anio, mes)})
