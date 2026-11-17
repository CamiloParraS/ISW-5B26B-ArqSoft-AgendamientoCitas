"""Riesgo R5 / ADR-002: el broker cae; la reserva sigue y el evento sale desde la outbox al volver."""
import time

import comun as c
from comun import log

NOMBRE = "broker-caido"
NODOS = ["rabbitmq-1", "rabbitmq-2", "rabbitmq-3"]
LIMITE_DRENAJE_S = 300  # umbral de la alarma R5


def escenario():
    return c.Escenario(
        NOMBRE, "Outbox: el broker de mensajes cae",
        "Falla de infraestructura",
        "Los 3 nodos de RabbitMQ se detienen mientras se agenda una cita",
        "API de Agendamiento (outbox + rele) y Servicio de Notificaciones",
        "Operacion normal",
        "La reserva se confirma igual; el evento queda pendiente en la outbox y se publica al volver el broker; llega el aviso",
        "0 eventos perdidos; reserva 201 con broker caido; outbox drenada y aviso entregado")


def pendiente(correlacion, cita_id):
    sql = ("SELECT count(*) FROM agenda.outbox WHERE publicado_en IS NULL AND "
           f"(correlacion_id = '{correlacion}' OR carga::text LIKE '%{cita_id}%')")
    return int(c.psql_filas(sql)[0][0])


def cuerpo(esc, limp):
    tok = c.token("ana")
    tok_coord = c.token("coordinacion")
    ids = []
    limp.agregar("cancelar la cita de prueba de Ana (Ortiz)", lambda: [c.cancelar(tok, i, timeout=15) for i in ids])
    limp.agregar("iniciar los 3 nodos de RabbitMQ y esperar healthy", lambda: (
        c.iniciar(*NODOS), [c.esperar_saludable(n, 240) for n in NODOS]))

    cupos = c.cupos_libres(tok, c.ESP_CARDIOLOGIA, c.MEDICO_ORTIZ, desde_dias=10, minimo=1)
    if not cupos:
        raise c.ErrorEscenario("No hay cupos libres del Dr. Ortiz")

    for n in NODOS:
        c.detener(n)
    log("broker caido (3 nodos detenidos)")

    corr = f"broker-{c.nuevo_id()[:8]}"
    cita = c.reservar_simple("ana", cupos[0], correlacion=corr)  # lanza si no es 200/201
    ids.append(cita["id"])
    log(f"reserva con el broker caido: cita {cita['id']}")
    time.sleep(5)  # el rele reintenta cada 500 ms sin exito
    pend_bd = pendiente(corr, cita["id"])
    r = c.peticion("GET", "/api/agenda/operacion/outbox", token=tok_coord, timeout=15)
    log(f"outbox (API): {r.resumen()}")
    pend_api = r.datos.get("pendientes") if r.ok and isinstance(r.datos, dict) else None
    log(f"outbox pendiente para la cita: {pend_bd}; pendientes totales (API): {pend_api}")

    t0 = time.monotonic()
    c.iniciar(*NODOS)
    for n in NODOS:
        c.esperar_saludable(n, 240)
    log("broker de nuevo arriba; esperando que la outbox se drene")
    drenado, _ = c.esperar_hasta(lambda: pendiente(corr, cita["id"]) == 0, LIMITE_DRENAJE_S, 2)
    seg_drenaje = time.monotonic() - t0

    def aviso():
        r = c.peticion("GET", f"{c.NOTIFICACIONES}/interno/envios?citaId={cita['id']}", timeout=10)
        return [e for e in c.lista_de(r.datos) if str(e.get("estado", "")).upper() == "ENVIADO"] if r.ok else None
    envio, _ = c.esperar_hasta(aviso, 180, 3)
    seg_aviso = time.monotonic() - t0
    if envio:
        log(f"aviso entregado: canal {envio[0].get('canal')} a los {seg_aviso:.0f} s de volver el broker")

    esc.mide("Reserva con el broker caido -> 201/200 (la reserva no depende del broker)", f"cita {cita['id']} estado {cita.get('estado')}", bool(cita.get("id")))
    esc.mide("Evento pendiente en la outbox con el broker caido (> 0)", f"{pend_bd} para la cita (API: {pend_api})", pend_bd > 0)
    esc.mide(f"Outbox drenada tras volver el broker (< {LIMITE_DRENAJE_S // 60} min, umbral de alarma R5)",
             f"{seg_drenaje:.0f} s" if drenado else "no se drenó", bool(drenado))
    esc.mide("Aviso de la cita llega al paciente tras volver el broker", f"{seg_aviso:.0f} s" if envio else "no llego", bool(envio))


def ejecutar():
    return c.correr(escenario(), cuerpo)
