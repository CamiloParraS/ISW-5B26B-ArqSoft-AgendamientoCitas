"""E1 - Disponibilidad: cae la instancia de la API y luego la BD primaria."""
import queue
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import comun as c
from comun import log

NOMBRE = "e1"
USUARIOS = [f"carga{n:02d}" for n in range(1, 6)]
RITMO_S = 4.0          # pausa de cada paciente entre reservas
LIMITE_RESERVA_S = 120  # una reserva se reintenta (misma clave) hasta este tiempo
MAX_RECUPERACION_S = 150


def escenario():
    return c.Escenario(
        NOMBRE, "Disponibilidad: cae la BD primaria",
        "Falla de infraestructura",
        "Se detiene una instancia de la API y luego la base de datos primaria deja de responder",
        "API de Agendamiento y PostgreSQL (Patroni)",
        "Carga continua de reservas (5 pacientes concurrentes, cada reserva = retener + confirmar con Idempotency-Key)",
        "La otra instancia atiende; la replica asume como primaria; las reservas en curso se reintentan con su clave de idempotencia",
        "Agendamiento restablecido en <= 60 s; 0 citas confirmadas perdidas")


class Estado:
    def __init__(self):
        self.lock = threading.Lock()
        self.exitos = []      # dicts: t0, t1, usuario, cita, http, instancia, reintentos
        self.fallos = []      # (t, usuario, descripcion)
        self.conflictos = 0
        self.abandonadas = 0  # reservas que no terminaron (sin respuesta definitiva)
        self.claves_con_reintento = 0

    def exito(self, **kw):
        with self.lock:
            self.exitos.append(kw)

    def fallo(self, usuario, desc):
        with self.lock:
            self.fallos.append((time.monotonic(), usuario, desc))


def reservar(usuario, cupo, est):
    """Retener + confirmar con reintento de la MISMA clave ante error de red o 5xx."""
    clave = c.nuevo_id()
    t0 = time.monotonic()
    reintentos = 0
    # 1) retencion (idempotente para el mismo paciente)
    while True:
        tok = c.token(usuario)
        r = c.retener(tok, cupo["id"], timeout=8)
        if r.status in (200, 201):
            break
        if r.status == 409:
            with est.lock:
                est.conflictos += 1
            return None
        est.fallo(usuario, f"retencion {r.status or r.error}")
        reintentos += 1
        if r.status in (400, 401, 403, 404, 422) or time.monotonic() - t0 > LIMITE_RESERVA_S:
            with est.lock:
                est.abandonadas += 1
            return None
        time.sleep(0.5)
    # 2) confirmacion con clave de idempotencia fija
    while True:
        tok = c.token(usuario)
        r = c.confirmar(tok, cupo["id"], clave, timeout=8)
        if r.status in (200, 201):
            cita = r.datos.get("id") if isinstance(r.datos, dict) else None
            if reintentos:
                with est.lock:
                    est.claves_con_reintento += 1
            est.exito(t0=t0, t1=time.monotonic(), usuario=usuario, cita=cita, http=r.status,
                      instancia=r.instancia, reintentos=reintentos)
            return cita
        if r.status == 409:
            with est.lock:
                est.conflictos += 1
            return None
        est.fallo(usuario, f"confirmacion {r.status or r.error}")
        reintentos += 1
        if r.status in (400, 401, 403, 404, 422) or time.monotonic() - t0 > LIMITE_RESERVA_S:
            with est.lock:
                est.abandonadas += 1
            return None
        time.sleep(0.5)


def trabajador(usuario, pool, est, parar):
    while not parar.is_set():
        try:
            cupo = pool.get_nowait()
        except queue.Empty:
            return
        try:
            reservar(usuario, cupo, est)
        except Exception as e:
            est.fallo(usuario, f"excepcion {e}")
        parar.wait(RITMO_S)


def primer_exito_desde(est, t_desde):
    """Primer exito cuya reserva EMPEZO despues de t_desde (no cuenta peticiones ya en vuelo)."""
    with est.lock:
        cand = [e for e in est.exitos if e["t0"] >= t_desde]
    return min(cand, key=lambda e: e["t1"]) if cand else None


def ultimo_exito_antes(est, t):
    with est.lock:
        cand = [e["t1"] for e in est.exitos if e["t1"] <= t]
    return max(cand) if cand else None


def cuerpo(esc, limp):
    est = Estado()
    parar = threading.Event()
    hilos = []

    # Precondiciones
    lider0 = c.lider_patroni()
    if not lider0:
        raise c.ErrorEscenario("Patroni no reporta lider (http://bd-agendamiento-N:8008/leader)")
    otro = "bd-agendamiento-2" if lider0 == "bd-agendamiento-1" else "bd-agendamiento-1"
    r = c.peticion("GET", f"http://{otro}:8008/replica", timeout=3, reintentos_429=0)
    log(f"lider inicial: {lider0}; replica {otro} /replica -> {r.status}")
    esc.nota(f"lider Patroni inicial: {lider0}; estado de la replica ({otro}): HTTP {r.status}")

    c.tokens_en_paralelo(USUARIOS)
    pool_cupos = c.cupos_libres(c.token(USUARIOS[0]), c.ESP_MEDICINA_GENERAL, c.MEDICO_ROSERO, desde_dias=2, minimo=300)
    log(f"cupos disponibles para la carga: {len(pool_cupos)}")
    if len(pool_cupos) < 60:
        raise c.ErrorEscenario(f"Insuficientes cupos libres de la Dra. Rosero para la carga ({len(pool_cupos)})")
    pool = queue.Queue()
    for cupo in pool_cupos:
        pool.put(cupo)

    # Limpieza (LIFO): primero reiniciar contenedores, luego cancelar citas de prueba
    def cancelar_todo():
        with ThreadPoolExecutor(len(USUARIOS)) as ex:
            n = sum(ex.map(c.cancelar_vigentes, USUARIOS))
        log(f"citas canceladas en la limpieza: {n}")
    limp.agregar("cancelar citas de los usuarios de carga", cancelar_todo)

    def restaurar():
        c.iniciar("bd-agendamiento-1", "bd-agendamiento-2", "agendamiento-1")
        for s in ("bd-agendamiento-1", "bd-agendamiento-2", "agendamiento-1", "agendamiento-2"):
            c.esperar_saludable(s, 240)
    limp.agregar("reiniciar agendamiento-1 y la BD detenida; esperar healthy", restaurar)

    def parar_hilos():
        parar.set()
        for h in hilos:
            h.join(timeout=LIMITE_RESERVA_S + 20)
    limp.agregar("detener la carga", parar_hilos)

    # Carga
    t_carga = time.monotonic()
    for u in USUARIOS:
        h = threading.Thread(target=trabajador, args=(u, pool, est, parar), daemon=True)
        h.start()
        hilos.append(h)
    log(f"carga iniciada: {len(USUARIOS)} pacientes, una reserva cada ~{RITMO_S:.0f} s por paciente")
    time.sleep(12)
    if len(est.exitos) < 3:
        raise c.ErrorEscenario(f"La carga base no logra reservar ({len(est.exitos)} exitos en 12 s); revise el stack")
    log(f"carga base estable: {len(est.exitos)} reservas en 12 s")

    # Fallo 1: cae agendamiento-1
    t_app = time.monotonic()
    c.detener("agendamiento-1")
    t_app_ret = time.monotonic()
    log(f"agendamiento-1 detenido (tardo {t_app_ret - t_app:.1f}s); observando 20 s")
    time.sleep(20)

    # Fallo 2: cae el lider de Patroni
    lider = c.lider_patroni() or lider0
    t_bd = time.monotonic()
    c.detener(lider)
    t_bd_ret = time.monotonic()
    log(f"primaria {lider} detenida (tardo {t_bd_ret - t_bd:.1f}s); esperando reservas exitosas nuevas")
    primero_bd = None
    while time.monotonic() - t_bd < MAX_RECUPERACION_S:
        primero_bd = primer_exito_desde(est, t_bd_ret)
        if primero_bd:
            break
        time.sleep(0.5)
    if primero_bd:
        log(f"agendamiento restablecido {primero_bd['t1'] - t_bd:.1f}s despues de iniciar el stop de la primaria "
            f"(instancia {primero_bd['instancia']})")
        nuevo = c.lider_patroni()
        log(f"nuevo lider Patroni: {nuevo}")
        esc.nota(f"nuevo lider Patroni tras la caida: {nuevo}")
    time.sleep(15)  # observar estabilidad
    parar_hilos()
    t_fin = time.monotonic()

    # ---- metricas
    with est.lock:
        exitos = sorted(est.exitos, key=lambda e: e["t1"])
        fallos = list(est.fallos)
    primero_app = primer_exito_desde(est, t_app_ret)
    restab_app = (primero_app["t1"] - t_app) if primero_app else float("inf")
    restab_bd = (primero_bd["t1"] - t_bd) if primero_bd else float("inf")
    tiempos = [t_carga] + [e["t1"] for e in exitos] + [t_fin]
    max_brecha = max((b - a for a, b in zip(tiempos, tiempos[1:])), default=0)
    ult_bd = ultimo_exito_antes(est, t_bd)
    brecha_bd = (primero_bd["t1"] - ult_bd) if (primero_bd and ult_bd) else float("inf")
    ult_app = ultimo_exito_antes(est, t_app)
    brecha_app = (primero_app["t1"] - ult_app) if (primero_app and ult_app) else float("inf")

    por_instancia = Counter(e["instancia"] for e in exitos)
    errores = Counter(d for _, _, d in fallos)
    log(f"reservas exitosas: {len(exitos)} por instancia {dict(por_instancia)}; conflictos {est.conflictos}; "
        f"abandonadas {est.abandonadas}; reservas que necesitaron reintento con la misma clave: {est.claves_con_reintento}")
    log(f"intentos fallidos por tipo: {dict(errores)}")
    log(f"ultimo exito antes -> primer exito despues: app {brecha_app:.1f}s, bd {brecha_bd:.1f}s; brecha maxima {max_brecha:.1f}s")

    # ---- verificacion de 0 citas perdidas (cada 200/201 debe existir en citas/mias de su dueno)
    esperadas = {}
    for e in exitos:
        if e["cita"]:
            esperadas.setdefault(e["usuario"], set()).add(e["cita"])
    perdidas, extra = [], 0
    for u, ids in esperadas.items():
        r = None
        for _ in range(20):
            r = c.mis_citas(c.token(u), timeout=15)
            if r.ok and isinstance(r.datos, list):
                break
            time.sleep(3)
        if not (r and r.ok and isinstance(r.datos, list)):
            raise c.ErrorEscenario(f"No se pudo consultar citas/mias de {u}: {r.resumen() if r else '-'}")
        presentes = {x["id"] for x in r.datos}
        perdidas += [i for i in ids if i not in presentes]
        confirmadas = {x["id"] for x in r.datos if x.get("estado") == "CONFIRMADA"}
        extra += len(confirmadas - ids)
    total_ids = sum(len(v) for v in esperadas.values())
    log(f"citas con 200/201: {total_ids}; perdidas: {len(perdidas)}; citas confirmadas sin respuesta registrada "
        f"(respuesta perdida, no duplicadas): {extra}")
    if extra:
        esc.nota(f"{extra} cita(s) quedaron confirmadas aunque el cliente no recibio la respuesta (no es perdida)")

    esc.mide("Reservas exitosas durante la prueba", len(exitos), len(exitos) >= 10)
    esc.mide("Tiempo hasta reservar de nuevo tras caer la BD primaria (<= 60 s)",
             f"{restab_bd:.1f} s" if restab_bd != float("inf") else "no se restablecio", restab_bd <= 60)
    esc.mide("Tiempo hasta reservar tras detener agendamiento-1 (<= 60 s)",
             f"{restab_app:.1f} s" if restab_app != float("inf") else "no se restablecio", restab_app <= 60)
    esc.mide("Brecha maxima entre reservas exitosas consecutivas (<= 60 s)", f"{max_brecha:.1f} s", max_brecha <= 60)
    esc.mide("Citas confirmadas perdidas (== 0)", f"{len(perdidas)} de {total_ids}", total_ids > 0 and not perdidas)


def ejecutar():
    return c.correr(escenario(), cuerpo)
