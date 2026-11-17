"""E2 - Escalabilidad: 50 pacientes compiten por el mismo cupo."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import comun as c
from comun import log

NOMBRE = "e2"
USUARIOS = [f"carga{n:02d}" for n in range(1, 51)]
RONDAS_P95 = 2


def escenario():
    return c.Escenario(
        NOMBRE, "Escalabilidad: carrera por el mismo cupo",
        "50 pacientes desde la app",
        "Intentan reservar el mismo cupo al mismo tiempo",
        "Modulo Agenda (API de Agendamiento + BD)",
        "Apertura de agenda de especialistas (carga concurrente sobre un cupo y sobre muchos cupos)",
        "Exactamente uno obtiene el cupo; los demas reciben los siguientes cupos libres",
        "0 dobles asignaciones; confirmacion p95 <= 800 ms")


def en_paralelo(funcion, usuarios, barrera=True):
    """Lanza funcion(usuario) en un hilo por usuario, todos a la vez (barrera)."""
    if not usuarios:
        return {}
    bar = threading.Barrier(len(usuarios)) if barrera else None
    res = {}

    def tarea(u):
        if bar:
            bar.wait(30)
        res[u] = funcion(u)
    with ThreadPoolExecutor(len(usuarios)) as ex:
        list(ex.map(tarea, usuarios))
    return res


def cuerpo(esc, limp):
    toks = c.tokens_en_paralelo(USUARIOS, 10)
    limp.agregar("cancelar las citas de carga01..carga50",
                 lambda: log(f"citas canceladas: {sum(ThreadPoolExecutor(25).map(c.cancelar_vigentes, USUARIOS))}"))

    minimo = 1 + 50 * RONDAS_P95 + 2
    cupos = c.cupos_libres(toks[USUARIOS[0]], c.ESP_PEDIATRIA, c.MEDICO_BURBANO, desde_dias=2, minimo=minimo)
    if len(cupos) < minimo:
        raise c.ErrorEscenario(f"Se necesitan {minimo} cupos libres del Dr. Burbano y hay {len(cupos)}")
    disputado, resto = cupos[0], cupos[1:]
    log(f"cupo disputado: {disputado['id']} ({disputado['inicio']})")

    # --- Fase 1: 50 retenciones simultaneas del mismo cupo
    res = en_paralelo(lambda u: c.retener(toks[u], disputado["id"], timeout=20, reintentos_429=0), USUARIOS)
    ganadores = [u for u, r in res.items() if r.status in (200, 201)]
    perdedores = [u for u, r in res.items() if r.status == 409]
    otros = {u: r.resumen() for u, r in res.items() if r.status not in (200, 201, 409)}
    con_alternativas = sum(1 for u in perdedores
                           if isinstance(res[u].datos, dict) and res[u].codigo() == "CUPO_NO_DISPONIBLE"
                           and res[u].datos.get("alternativas"))
    log(f"retenciones: {len(ganadores)} ganador(es) {ganadores}, {len(perdedores)} x 409, otros {otros}")

    # --- Fase 2: el ganador confirma; los perdedores tambien lo intentan (deben fallar)
    latencias = []
    conf_ganador = {}

    def confirmar_disputado(u):
        r = c.confirmar(toks[u], disputado["id"], c.nuevo_id(), timeout=20, reintentos_429=0)
        return r
    res2 = en_paralelo(confirmar_disputado, USUARIOS)
    exitos_disputado = [u for u, r in res2.items() if r.status in (200, 201)]
    for u in exitos_disputado:
        latencias.append(res2[u].seg * 1000)
        conf_ganador[u] = res2[u].datos
    log(f"confirmaciones del cupo disputado con 2xx: {exitos_disputado}")

    # --- Fase 3: muchas confirmaciones concurrentes sobre cupos distintos para medir p95
    errores_p95 = 0
    for ronda in range(RONDAS_P95):
        lote = resto[ronda * 50:(ronda + 1) * 50]
        asign = dict(zip(USUARIOS, lote))
        ret = en_paralelo(lambda u: c.retener(toks[u], asign[u]["id"], timeout=20, reintentos_429=0), USUARIOS)
        malos = [u for u, r in ret.items() if r.status not in (200, 201)]
        if malos:
            log(f"ronda {ronda + 1}: {len(malos)} retenciones no exitosas, ej. {ret[malos[0]].resumen()}")
        usuarios_ok = [u for u in USUARIOS if u not in malos]
        conf = en_paralelo(lambda u: c.confirmar(toks[u], asign[u]["id"], c.nuevo_id(), timeout=20, reintentos_429=0),
                           usuarios_ok)
        for u, r in conf.items():
            if r.status in (200, 201):
                latencias.append(r.seg * 1000)
            else:
                errores_p95 += 1
                log(f"ronda {ronda + 1}: confirmacion de {u} -> {r.resumen()}")
        time.sleep(1)

    p50, p95, pmax = c.percentil(latencias, 50), c.percentil(latencias, 95), max(latencias, default=0)
    log(f"confirmaciones medidas: {len(latencias)}; p50 {p50:.0f} ms, p95 {p95:.0f} ms, max {pmax:.0f} ms")

    # --- Verificacion en la BD (lider Patroni)
    dobles = int(c.psql_filas("SELECT count(*) FROM (SELECT cupo_id FROM agenda.cita WHERE estado <> 'CANCELADA' "
                              "GROUP BY cupo_id HAVING count(*) > 1) x")[0][0])
    en_disputado = int(c.psql_filas(f"SELECT count(*) FROM agenda.cita WHERE cupo_id = '{disputado['id']}' "
                                    "AND estado <> 'CANCELADA'")[0][0])
    log(f"BD: cupos con >1 cita no cancelada = {dobles}; citas activas del cupo disputado = {en_disputado}")

    esc.mide("Retenciones ganadoras del cupo disputado (== 1)", len(ganadores), len(ganadores) == 1)
    esc.mide("Respuestas 409 con alternativas (== 49)", f"{len(perdedores)} x 409, {con_alternativas} con alternativas",
             len(perdedores) == 49 and con_alternativas == 49 and not otros)
    esc.mide("Confirmaciones exitosas del cupo disputado (== 1, solo el ganador)",
             len(exitos_disputado), exitos_disputado == ganadores and len(exitos_disputado) == 1)
    esc.mide("Dobles asignaciones en BD (== 0)", f"{dobles} cupos con >1 cita; {en_disputado} cita activa en el disputado",
             dobles == 0 and en_disputado == 1)
    esc.mide(f"Confirmacion p95 sobre {len(latencias)} confirmaciones (<= 800 ms)", f"{p95:.0f} ms (p50 {p50:.0f}, max {pmax:.0f})",
             len(latencias) >= 50 and p95 <= 800 and errores_p95 == 0)


def ejecutar():
    return c.correr(escenario(), cuerpo)
