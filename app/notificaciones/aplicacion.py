"""Casos de uso de Notificaciones.

Degradación elegante (D-2): el envío se registra como PENDIENTE dentro de la misma transacción de la
reserva; después se intenta enviar. Si el proveedor falla, la reserva ya quedó confirmada y el barrido
reintenta el envío.
"""
import logging
import threading

from django.conf import settings
from django.db import connection, transaction
from django.utils import timezone

from identidad import api as identidad

from . import canales
from . import dominio as d
from . import models as m

log = logging.getLogger(__name__)


# --- Manejadores de los eventos de cita de Agenda ---

def al_reservar(evento):
    _encolar(d.CONFIRMACION, evento)


def al_cancelar(evento):
    _encolar(d.CANCELACION, evento)


def al_recordatorio(evento):
    _encolar(d.RECORDATORIO, evento)


def al_inasistencia(evento):
    _encolar(d.INASISTENCIA, evento)


def _encolar(tipo, evento):
    canal = canales.obtener_canal()
    contacto = identidad.contacto(evento.paciente_id)
    destino = contacto.telefono if canal.nombre == "sms" else contacto.correo
    medico = identidad.nombres([evento.medico_id]).get(evento.medico_id, "su médico")
    envio, creado = m.Envio.objects.get_or_create(
        tipo=tipo,
        cita_id=evento.cita_id,
        defaults={
            "paciente_id": evento.paciente_id,
            "canal": canal.nombre,
            "destino": destino,
            "mensaje": d.redactar(tipo, medico, timezone.localtime(evento.inicio)),
        },
    )
    if creado and settings.NOTIFICACIONES_ENVIO_INMEDIATO:
        transaction.on_commit(lambda: _lanzar_envio(envio.id))


def _lanzar_envio(envio_id):
    threading.Thread(target=_enviar_en_segundo_plano, args=(envio_id,), name=f"envio-{envio_id}").start()


def _enviar_en_segundo_plano(envio_id):
    try:
        enviar_uno(envio_id)
    except Exception:  # noqa: BLE001 - nunca debe tumbar el proceso; queda pendiente para el barrido
        log.exception("Falló el envío inmediato %s; queda pendiente", envio_id)
    finally:
        connection.close()


def _intentar(envio):
    envio.intentos += 1
    try:
        canales.obtener_canal(envio.canal).enviar(envio.destino, envio.mensaje)
    except canales.ErrorDeCanal as e:
        envio.ultimo_error = str(e)[:500]
        envio.save(update_fields=["intentos", "ultimo_error"])
        log.warning("Envío %s pendiente: %s", envio.id, e)
        return False
    envio.estado = d.ENVIADO
    envio.enviado_en = timezone.now()
    envio.ultimo_error = ""
    envio.save(update_fields=["intentos", "estado", "enviado_en", "ultimo_error"])
    return True


def enviar_uno(envio_id):
    with transaction.atomic():
        envio = m.Envio.objects.select_for_update(skip_locked=True).filter(id=envio_id, estado=d.PENDIENTE).first()
        return _intentar(envio) if envio else False


def enviar_pendientes(limite=100):
    """Reintento desde el barrido. Si el proveedor sigue caído, se detiene al primer fallo."""
    enviados = 0
    with transaction.atomic():
        lote = list(m.Envio.objects.select_for_update(skip_locked=True).filter(estado=d.PENDIENTE).order_by("id")[:limite])
        for envio in lote:
            if not _intentar(envio):
                break
            enviados += 1
    return {"enviados": enviados, "pendientes": m.Envio.objects.filter(estado=d.PENDIENTE).count()}


def del_paciente(paciente_id, limite=50):
    return [
        {
            "id": e.id,
            "tipo": e.tipo,
            "cita_id": e.cita_id,
            "canal": e.canal,
            "destino": e.destino,
            "mensaje": e.mensaje,
            "estado": e.estado,
            "intentos": e.intentos,
            "creado": timezone.localtime(e.creado).isoformat(),
            "enviado_en": timezone.localtime(e.enviado_en).isoformat() if e.enviado_en else None,
        }
        for e in m.Envio.objects.filter(paciente_id=paciente_id).order_by("-id")[:limite]
    ]
