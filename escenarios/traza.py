"""Tactica 'trazas correlacionadas': un X-Correlation-Id conocido viaja de la app a WhatsApp."""
import time

import comun as c
from comun import log

NOMBRE = "traza"


def escenario():
    return c.Escenario(
        NOMBRE, "Observabilidad: traza correlacionada de punta a punta",
        "Paciente (app) que agenda una cita",
        "Reserva con un X-Correlation-Id conocido",
        "API Gateway, API de Agendamiento (outbox), broker, Servicio de Notificaciones, WhatsApp",
        "Operacion normal",
        "El mismo ID aparece en el log del gateway, el log de la API, la fila de la outbox, el envio de Notificaciones y el registro de WhatsApp",
        "ID presente en las 5 ubicaciones")


def lineas(texto, clave, n=2):
    return [l.strip()[:230] for l in texto.splitlines() if clave in l][:n]


def cuerpo(esc, limp):
    tok = c.token("ana")
    ids = []
    limp.agregar("cancelar la cita de prueba de Ana (Rosero)", lambda: [c.cancelar(tok, i, timeout=15) for i in ids])
    cupos = c.cupos_libres(tok, c.ESP_MEDICINA_GENERAL, c.MEDICO_ROSERO, desde_dias=16, minimo=1)
    if not cupos:
        raise c.ErrorEscenario("No hay cupos libres de la Dra. Rosero")
    corr = f"traza-{c.nuevo_id()}"
    cita = c.reservar_simple("ana", cupos[0], correlacion=corr)
    ids.append(cita["id"])
    t_conf = time.monotonic()
    log(f"cita {cita['id']} agendada con X-Correlation-Id={corr}")

    # 1) gateway
    gw = c.logs("gateway", "10m")
    l_gw = lineas(gw, corr)
    # 2) API (ambas instancias)
    l_api = []
    for s in ("agendamiento-1", "agendamiento-2"):
        try:
            l_api += [f"[{s}] {x}" for x in lineas(c.logs(s, "10m"), corr)]
        except c.ErrorEscenario as e:
            log(f"  {e}")
    # 3) outbox
    filas = c.psql_filas("SELECT tipo, routing_key, correlacion_id, publicado_en IS NOT NULL FROM agenda.outbox "
                         f"WHERE correlacion_id = '{corr}'")
    # 4) envio de notificaciones y 5) registro de WhatsApp (asincronos: esperar)

    def en_envios():
        r = c.peticion("GET", f"{c.NOTIFICACIONES}/interno/envios?citaId={cita['id']}", timeout=10)
        return [e for e in c.lista_de(r.datos) if corr in str(e)] if r.ok else None
    envios, _ = c.esperar_hasta(en_envios, 90, 2)

    def en_whatsapp():
        r = c.peticion("GET", f"{c.SIMULADORES}/registro/whatsapp", timeout=10)
        return [e for e in c.lista_de(r.datos) if corr in str(e)] if r.ok else None
    wa, _ = c.esperar_hasta(en_whatsapp, 90, 0.5)
    seg_wa = time.monotonic() - t_conf

    for nombre, ev in (("gateway", l_gw), ("agendamiento", l_api), ("outbox", filas),
                       ("notificaciones.envio", envios), ("whatsapp registro", wa)):
        log(f"{nombre}: {len(ev or [])} coincidencia(s)")
        for x in (ev or [])[:2]:
            log(f"    {str(x)[:230]}")

    esc.mide("Log del API Gateway contiene el ID", f"{len(l_gw)} lineas", bool(l_gw))
    esc.mide("Log de la API de Agendamiento contiene el ID", f"{len(l_api)} lineas", bool(l_api))
    esc.mide("Fila de la outbox guarda el ID", f"{len(filas)} fila(s)", bool(filas))
    esc.mide("Envio de Notificaciones guarda el ID", f"{len(envios or [])} envio(s)", bool(envios))
    esc.nota(f"Informativo (hoja B,A, negociable: recordatorio <= 5 s): confirmacion -> registro de WhatsApp en {seg_wa:.1f} s" if wa else "Informativo: el mensaje no llego al registro de WhatsApp")
    log(f"confirmacion -> registro WhatsApp: {seg_wa:.1f} s")
    esc.mide("Registro del simulador de WhatsApp recibio el ID", f"{len(wa or [])} mensaje(s)", bool(wa))


def ejecutar():
    return c.correr(escenario(), cuerpo)
