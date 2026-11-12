"""Casos de uso de Agenda."""
import logging
from collections import defaultdict
from zoneinfo import ZoneInfo

from django.conf import settings
from django.db import IntegrityError, transaction
from django.db.models import Count, Max
from django.utils import timezone

from identidad import api as identidad

from . import dominio as d
from . import models as m

log = logging.getLogger(__name__)

# --- Eventos en proceso (sin broker, ADR-001): los manejadores corren dentro de la misma transacción ---
_suscriptores = defaultdict(list)


def suscribir(tipo_evento, manejador):
    if manejador not in _suscriptores[tipo_evento]:
        _suscriptores[tipo_evento].append(manejador)


def _publicar(evento):
    for manejador in _suscriptores[type(evento)]:
        manejador(evento)


def _iso(dt):
    return timezone.localtime(dt).isoformat()


def _cupo_dict(c):
    return {"id": c.id, "medico_id": c.medico_id, "inicio": _iso(c.inicio), "fin": _iso(c.fin), "estado": c.estado}


def _cita_dict(c):
    return {
        "id": c.id,
        "cupo_id": c.cupo_id,
        "paciente_id": c.paciente_id,
        "medico_id": c.medico_id,
        "inicio": _iso(c.inicio),
        "fin": _iso(c.fin),
        "estado": c.estado,
    }


# --- Horarios y cupos (Recepción) ---

def crear_horario(medico_id, fecha, hora_inicio, hora_fin, duracion_minutos):
    medico = identidad.obtener(medico_id)
    if medico is None or medico.rol != identidad.MEDICO:
        raise d.ReglaIncumplida("El médico no existe")
    franjas = d.generar_franjas(fecha, hora_inicio, hora_fin, duracion_minutos, ZoneInfo(settings.TIME_ZONE))
    existentes = m.Cupo.objects.filter(medico_id=medico_id, inicio__in=[i for i, _ in franjas]).count()
    m.Cupo.objects.bulk_create([m.Cupo(medico_id=medico_id, inicio=i, fin=f) for i, f in franjas], ignore_conflicts=True)
    return {"creados": len(franjas) - existentes, "existentes": existentes}


def crear_cupo(medico_id, inicio, fin):
    cupo, _ = m.Cupo.objects.get_or_create(medico_id=medico_id, inicio=inicio, defaults={"fin": fin})
    return _cupo_dict(cupo)


def cupos_disponibles(medico_id, desde=None, hasta=None, ahora=None, limite=200):
    ahora = ahora or timezone.now()
    qs = m.Cupo.objects.filter(medico_id=medico_id, estado=d.LIBRE, inicio__gt=ahora)
    if desde:
        qs = qs.filter(inicio__gte=desde)
    if hasta:
        qs = qs.filter(inicio__lt=hasta)
    return [_cupo_dict(c) for c in qs.order_by("inicio")[:limite]]


def _alternativas(cupo_id, ahora, cantidad=3):
    cupo = m.Cupo.objects.filter(id=cupo_id).first()
    if cupo is None:
        return []
    libres = m.Cupo.objects.filter(medico_id=cupo.medico_id, estado=d.LIBRE, inicio__gt=ahora).exclude(id=cupo_id)
    despues = list(libres.filter(inicio__gte=cupo.inicio).order_by("inicio")[:cantidad])
    if len(despues) < cantidad:
        despues += list(libres.filter(inicio__lt=cupo.inicio).order_by("-inicio")[: cantidad - len(despues)])
    return [_cupo_dict(c) for c in despues]


# --- Reserva (ADR-002: escritura condicional + índice único + idempotencia) ---

def _tomar_cupo(paciente_id, cupo_id, ahora, permitir_pasado):
    # Táctica "Escritura condicional": UPDATE cupo ... WHERE estado='LIBRE' dentro de la transacción.
    filtro = m.Cupo.objects.filter(id=cupo_id, estado=d.LIBRE)
    if not permitir_pasado:
        filtro = filtro.filter(inicio__gt=ahora)
    if filtro.update(estado=d.TOMADO) == 0:
        cupo = m.Cupo.objects.filter(id=cupo_id).first()
        if cupo is None:
            raise d.CupoInexistente("El cupo no existe")
        if cupo.estado == d.LIBRE:
            raise d.ReglaIncumplida("El cupo ya pasó")
        raise d.CupoYaTomado(_alternativas(cupo_id, ahora))
    cupo = m.Cupo.objects.get(id=cupo_id)
    return m.Cita.objects.create(
        cupo=cupo, paciente_id=paciente_id, medico_id=cupo.medico_id, inicio=cupo.inicio, fin=cupo.fin, estado=d.CONFIRMADA
    )


def reservar(paciente_id, cupo_id, clave, ahora=None, permitir_pasado=False):
    """Devuelve (cita, creada). Un reintento con la misma clave devuelve la misma cita con creada=False."""
    if not clave:
        raise d.ReglaIncumplida("Falta la clave de idempotencia")
    ahora = ahora or timezone.now()
    try:
        with transaction.atomic():
            previa = m.SolicitudReserva.objects.select_related("cita").filter(paciente_id=paciente_id, clave=clave).first()
            if previa is not None:
                return _cita_dict(previa.cita), False
            # Si otra solicitud con la misma clave está en curso, este INSERT espera y falla al confirmarse aquella.
            solicitud = m.SolicitudReserva.objects.create(paciente_id=paciente_id, clave=clave)
            cita = _tomar_cupo(paciente_id, cupo_id, ahora, permitir_pasado)
            solicitud.cita = cita
            solicitud.save(update_fields=["cita"])
            _publicar(d.CitaReservada(cita.id, cita.paciente_id, cita.medico_id, cita.inicio))
            return _cita_dict(cita), True
    except IntegrityError:
        previa = (
            m.SolicitudReserva.objects.select_related("cita")
            .filter(paciente_id=paciente_id, clave=clave, cita__isnull=False)
            .first()
        )
        if previa is not None:
            return _cita_dict(previa.cita), False
        # El índice único rechazó una segunda cita vigente para el cupo.
        raise d.CupoYaTomado(_alternativas(cupo_id, ahora))


def cancelar(cita_id, paciente_id=None, ahora=None):
    ahora = ahora or timezone.now()
    with transaction.atomic():
        qs = m.Cita.objects.select_for_update().filter(id=cita_id, estado=d.CONFIRMADA)
        if paciente_id is not None:
            qs = qs.filter(paciente_id=paciente_id)
        cita = qs.first()
        if cita is None:
            raise d.CitaNoEncontrada("No hay una cita confirmada con ese número")
        if cita.inicio <= ahora:
            raise d.ReglaIncumplida("La cita ya comenzó; no se puede cancelar")
        m.Cita.objects.filter(id=cita.id, estado=d.CONFIRMADA).update(estado=d.CANCELADA, actualizada=ahora)
        m.Cupo.objects.filter(id=cita.cupo_id, estado=d.TOMADO).update(estado=d.LIBRE)
        _publicar(d.CitaCancelada(cita.id, cita.paciente_id, cita.medico_id, cita.inicio))
        cita.estado = d.CANCELADA
        return _cita_dict(cita)


def reprogramar(paciente_id, cita_id, nuevo_cupo_id, clave, ahora=None):
    """Toma el cupo nuevo y libera el anterior en una sola transacción. Idempotente por clave."""
    ahora = ahora or timezone.now()
    with transaction.atomic():
        actual = m.Cita.objects.select_for_update().filter(id=cita_id, paciente_id=paciente_id).first()
        if actual is None:
            raise d.CitaNoEncontrada("No hay una cita con ese número")
        nueva, creada = reservar(paciente_id, nuevo_cupo_id, clave, ahora)
        if creada:
            if actual.estado != d.CONFIRMADA:
                raise d.ReglaIncumplida("Solo se puede reprogramar una cita confirmada")
            cancelar(cita_id, paciente_id, ahora)
        return nueva, creada


def marcar_asistencia(medico_id, cita_id, ahora=None):
    ahora = ahora or timezone.now()
    n = m.Cita.objects.filter(id=cita_id, medico_id=medico_id, estado=d.CONFIRMADA).update(estado=d.ATENDIDA, actualizada=ahora)
    if n == 0:
        raise d.CitaNoEncontrada("No hay una cita confirmada suya con ese número")
    return _cita_dict(m.Cita.objects.get(id=cita_id))


def citas(paciente_id=None, medico_id=None, desde=None, hasta=None, limite=500):
    qs = m.Cita.objects.all()
    if paciente_id is not None:
        qs = qs.filter(paciente_id=paciente_id)
    if medico_id is not None:
        qs = qs.filter(medico_id=medico_id)
    if desde:
        qs = qs.filter(inicio__gte=desde)
    if hasta:
        qs = qs.filter(inicio__lt=hasta)
    return [_cita_dict(c) for c in qs.order_by("inicio")[:limite]]


# --- Barrido de vencimientos (lo dispara la Tarea programada cada 5 min) ---

def barrer_vencimientos(ahora=None):
    ahora = ahora or timezone.now()
    with transaction.atomic():
        ejecucion = m.EjecucionBarrido.objects.create(inicio=ahora)
        inasistencias = m.marcar_inasistencias(d.limite_inasistencia(ahora), ahora)
        for cita_id, paciente_id, medico_id, inicio in inasistencias:
            _publicar(d.InasistenciaRegistrada(cita_id, paciente_id, medico_id, inicio))
        desde, hasta = d.ventana_recordatorio(ahora)
        recordatorios = m.marcar_recordatorios(desde, hasta, ahora)
        for cita_id, paciente_id, medico_id, inicio in recordatorios:
            _publicar(d.RecordatorioDebido(cita_id, paciente_id, medico_id, inicio))
        ejecucion.fin = timezone.now()
        ejecucion.inasistencias = len(inasistencias)
        ejecucion.recordatorios = len(recordatorios)
        ejecucion.save()
    return {"inasistencias": len(inasistencias), "recordatorios": len(recordatorios)}


def ultimo_barrido():
    return m.EjecucionBarrido.objects.filter(fin__isnull=False).aggregate(ultimo=Max("fin"))["ultimo"]


# --- Reporte mensual (P-2) ---

def reporte_mensual(anio, mes):
    filas = defaultdict(lambda: {"cupos": 0, **{e: 0 for e in d.ESTADOS_CITA}})
    for f in m.Cupo.objects.filter(inicio__year=anio, inicio__month=mes).values("medico_id").annotate(total=Count("id")):
        filas[f["medico_id"]]["cupos"] = f["total"]
    citas_mes = m.Cita.objects.filter(inicio__year=anio, inicio__month=mes)
    for f in citas_mes.values("medico_id", "estado").annotate(total=Count("id")):
        filas[f["medico_id"]][f["estado"]] = f["total"]
    nombres = identidad.nombres(filas.keys())
    return [
        {"medico_id": medico_id, "medico": nombres.get(medico_id, f"#{medico_id}"), **valores}
        for medico_id, valores in sorted(filas.items())
    ]
