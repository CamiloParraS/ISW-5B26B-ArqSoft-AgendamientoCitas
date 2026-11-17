"""Riesgo R3: Meta rechaza la plantilla de WhatsApp -> respaldo automatico a SMS en <= 2 min."""
import time

import comun as c
from comun import log

NOMBRE = "respaldo-canal"
PLANTILLA = "cita_confirmada"
LIMITE_S = 120


def escenario():
    return c.Escenario(
        NOMBRE, "Respaldo de canal: plantilla de WhatsApp rechazada",
        "Sistema externo (Meta rechaza o pausa una plantilla)",
        "La plantilla 'cita_confirmada' queda rechazada y una paciente que prefiere WhatsApp agenda una cita",
        "Servicio de Notificaciones (puerto CanalNotificacion y adaptadores)",
        "Operacion normal",
        "El envio por WhatsApp queda RECHAZADO y el respaldo automatico lo entrega por SMS",
        "Respaldo a SMS medido en <= 2 min")


def plantilla(rechazada):
    return c.peticion("POST", f"{c.SIMULADORES}/control/whatsapp/plantillas/{PLANTILLA}",
                      cuerpo={"rechazada": rechazada}, timeout=10)


def envios(cita_id):
    r = c.peticion("GET", f"{c.NOTIFICACIONES}/interno/envios?citaId={cita_id}", timeout=10)
    return c.lista_de(r.datos) if r.ok else []


def campo(e, *claves):
    for k in claves:
        if k in e and e[k] is not None:
            return str(e[k]).upper()
    return ""


def cuerpo(esc, limp):
    tok = c.token("ana")
    limp.agregar("restablecer plantilla cita_confirmada (rechazada=false)", lambda: plantilla(False))
    ids = []
    limp.agregar("cancelar la cita de prueba de Ana (Burbano)", lambda: [c.cancelar(tok, i, timeout=15) for i in ids])

    cupos = c.cupos_libres(tok, c.ESP_PEDIATRIA, c.MEDICO_BURBANO, desde_dias=10, minimo=1)
    if not cupos:
        raise c.ErrorEscenario("No hay cupos libres del Dr. Burbano")
    r = plantilla(True)
    if not r.ok:
        raise c.ErrorEscenario(f"No se pudo rechazar la plantilla: {r.resumen()}")
    log(f"plantilla {PLANTILLA} marcada como rechazada en el simulador de WhatsApp")

    cita = c.reservar_simple("ana", cupos[0])
    ids.append(cita["id"])
    t0 = time.monotonic()
    log(f"cita agendada: {cita['id']}; esperando envio por WhatsApp (RECHAZADO) y SMS (ENVIADO)")

    def respaldo():
        lista = envios(cita["id"])
        sms = [e for e in lista if campo(e, "canal") == "SMS" and campo(e, "estado") == "ENVIADO"]
        return (lista, sms) if sms else None
    res, _ = c.esperar_hasta(respaldo, LIMITE_S + 60, 1.0)
    seg = time.monotonic() - t0
    lista = res[0] if res else envios(cita["id"])
    for e in lista:
        log(f"  envio: canal={campo(e, 'canal')} estado={campo(e, 'estado')} tipo={campo(e, 'tipo')} detalle={str(e.get('detalle', ''))[:80]!r}")
    wa_rechazado = any(campo(e, "canal") == "WHATSAPP" and campo(e, "estado") == "RECHAZADO" for e in lista)
    sms_ok = res is not None

    reg = c.peticion("GET", f"{c.SIMULADORES}/registro/sms", timeout=10)
    log(f"simulador SMS: {len(c.lista_de(reg.datos))} mensajes registrados")

    esc.mide("Envio por WhatsApp RECHAZADO (plantilla pausada/rechazada)", "si" if wa_rechazado else "no", wa_rechazado)
    esc.mide("Entrega por SMS (ENVIADO) tras el rechazo (<= 2 min desde la reserva)",
             f"{seg:.1f} s" if sms_ok else "no llego", sms_ok and seg <= LIMITE_S)


def ejecutar():
    return c.correr(escenario(), cuerpo)
