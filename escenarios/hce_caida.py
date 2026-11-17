"""Hoja HCE caida: la cita se agenda igual y la HCE la ve despues."""
import time
import urllib.parse

import comun as c
from comun import log

NOMBRE = "hce-caida"
CITAS = 4        # suficientes eventos para superar minimumNumberOfCalls=5 del circuit breaker
ATENDIDAS = 2
LIMITE_S = 15 * 60


def escenario():
    return c.Escenario(
        NOMBRE, "Disponibilidad/Interoperabilidad: la HCE no responde",
        "Falla de un sistema externo (HCE del hospital)",
        "La HCE (FHIR R4) deja de responder (503) mientras se agendan y atienden citas",
        "Servicio de Integracion Clinica (circuit breaker) y broker",
        "Operacion normal; los eventos esperan en la cola de reintento",
        "La cita se agenda igual; el circuito se abre; al volver la HCE la cita atendida llega como Appointment y Encounter",
        "Agendamiento sigue en 201; Appointment + Encounter en la HCE <= 15 min despues de restablecerla")


def control_hce(caida):
    return c.peticion("POST", f"{c.SIMULADORES}/control/hce", cuerpo={"caida": caida}, timeout=10)


def fhir_existe(tipo, cita_id):
    ident = urllib.parse.quote(f"urn:medicita:cita|{cita_id}", safe="")
    r = c.peticion("GET", f"{c.SIMULADORES}/fhir/{tipo}?identifier={ident}&_format=json", timeout=10,
                   cabeceras={"Accept": "application/fhir+json"})
    if r.status != 200 or not isinstance(r.datos, dict):
        return None
    return r.datos


def estado_circuito():
    r = c.peticion("GET", f"{c.INTEGRACION}/interno/hce/estado", timeout=5)
    return (r.datos or {}).get("estadoCircuito") if r.ok and isinstance(r.datos, dict) else None


def cuerpo(esc, limp):
    tok_ana, tok_doc = c.token("ana"), c.token("dra.rosero")
    creadas_sin_atender = []
    limp.agregar("cancelar citas pendientes de Ana (Rosero)", lambda: [
        c.cancelar(tok_ana, i, timeout=15) for i in creadas_sin_atender])
    limp.agregar("restablecer la HCE (caida=false)", lambda: control_hce(False))

    cupos = c.cupos_libres(tok_ana, c.ESP_MEDICINA_GENERAL, c.MEDICO_ROSERO, desde_dias=10, minimo=CITAS)
    if len(cupos) < CITAS:
        raise c.ErrorEscenario(f"Se necesitan {CITAS} cupos libres de la Dra. Rosero")

    r = control_hce(True)
    if not r.ok:
        raise c.ErrorEscenario(f"POST /control/hce fallo: {r.resumen()}")
    log("HCE simulada CAIDA")

    citas, atendidas = [], []
    for i, cupo in enumerate(cupos[:CITAS]):
        cita = c.reservar_simple("ana", cupo)  # lanza si no devuelve 200/201
        citas.append(cita)
        log(f"cita {i + 1}/{CITAS} agendada con HCE caida: {cita['id']}")
    for cita in citas[:ATENDIDAS]:
        r = c.peticion("POST", f"/api/agenda/medico/citas/{cita['id']}/atencion", token=tok_doc, timeout=15)
        if not r.ok:
            raise c.ErrorEscenario(f"marcar atendida {cita['id']}: {r.resumen()}")
        atendidas.append(cita["id"])
    creadas_sin_atender.extend(x["id"] for x in citas[ATENDIDAS:])
    log(f"{len(citas)} citas agendadas (201) y {len(atendidas)} marcadas atendidas con la HCE caida")

    abierto, seg_abrir = c.esperar_hasta(lambda: estado_circuito() in ("OPEN", "HALF_OPEN"), 120, 3)
    estado = estado_circuito()
    log(f"estado del circuito: {estado} (abierto en {seg_abrir:.0f} s)" if abierto else f"el circuito no se abrio; estado {estado}")

    # Nada de las citas debe estar aun en la HCE
    time.sleep(2)
    reg = c.peticion("GET", f"{c.SIMULADORES}/registro/hce", timeout=10)
    # entradas del registro que mencionan la cita solo cuentan si no fueron rechazadas con 503
    efectivas = []
    for e in c.lista_de(reg.datos):
        t = str(e)
        if any(x["id"] in t for x in citas) and "503" not in t:
            efectivas.append(t[:120])
    log(f"entradas del registro HCE con las citas (sin 503) mientras estaba caida: {len(efectivas)}")

    # Restablecer y medir
    t_restab = time.monotonic()
    r = control_hce(False)
    if not r.ok:
        raise c.ErrorEscenario(f"restablecer HCE fallo: {r.resumen()}")
    log("HCE restablecida; esperando Appointment + Encounter (limite 15 min)")

    def todo_llego():
        for cid in atendidas:
            for tipo in ("Appointment", "Encounter"):
                b = fhir_existe(tipo, cid)
                if not b or not (b.get("entry") or b.get("total", 0) > 0):
                    return False
        for cita in citas[ATENDIDAS:]:
            b = fhir_existe("Appointment", cita["id"])
            if not b or not (b.get("entry") or b.get("total", 0) > 0):
                return False
        return True
    ok, seg = c.esperar_hasta(todo_llego, LIMITE_S, 5)
    seg = time.monotonic() - t_restab
    log(f"sincronizacion con la HCE: {'completa' if ok else 'INCOMPLETA'} a los {seg:.0f} s")
    if ok:
        b = fhir_existe("Appointment", atendidas[0])
        try:
            log(f"Appointment de la cita atendida: status={b['entry'][0]['resource'].get('status')}")
        except Exception:
            pass

    esc.mide("Reservas con la HCE caida -> 201", f"{len(citas)} de {CITAS}", len(citas) == CITAS)
    esc.mide("Circuit breaker abierto (OPEN/HALF_OPEN) con la HCE caida", f"{estado}", bool(abierto))
    esc.mide("HCE sin recursos de las citas mientras estaba caida", f"{len(efectivas)} entradas", len(efectivas) == 0)
    esc.mide("Appointment + Encounter en la HCE tras restablecerla (<= 15 min)",
             f"{seg:.0f} s ({'esperado < 2 min' if ok and seg < 120 else 'fuera de lo esperado' if ok else 'no llegaron'})",
             bool(ok) and seg <= LIMITE_S)


def ejecutar():
    return c.correr(escenario(), cuerpo)
