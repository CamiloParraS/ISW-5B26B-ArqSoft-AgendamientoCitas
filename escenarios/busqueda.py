"""Hoja de rendimiento (A,A): buscar cupos responde p95 <= 500 ms."""
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import comun as c
from comun import log

NOMBRE = "busqueda"
USUARIOS = [f"carga{n:02d}" for n in range(1, 51)]
DURACION_S = 30
PAUSA_S = 0.15  # evita el limite de tasa de la zona 'api' (20 r/s por usuario)
ESPECIALIDADES = [c.ESP_MEDICINA_GENERAL, c.ESP_PEDIATRIA, c.ESP_CARDIOLOGIA]


def escenario():
    return c.Escenario(
        NOMBRE, "Rendimiento: buscar cupos con carga concurrente",
        "50 pacientes desde la app (escala reducida)",
        "Buscan cupos repetidamente: primer cupo disponible y cupos por especialidad y fecha",
        "API de Agendamiento + Cache de cupos (Redis)",
        "Apertura de agenda simulada: 50 usuarios concurrentes durante ~30 s",
        "Las busquedas leen cupos libres de la cache; la confirmacion nunca depende de ella",
        "Buscar cupos p95 <= 500 ms")


def cuerpo(esc, limp):
    toks = c.tokens_en_paralelo(USUARIOS, 10)
    hoy = c.ahora_bogota().date()
    fechas = []
    d = 2
    while len(fechas) < 5 and d < 21:
        f = hoy + c.timedelta(days=d)
        if f.weekday() < 5:
            fechas.append(f.isoformat())
        d += 1
    lat, cache, estados = [], Counter(), Counter()
    por_ruta = {'proximos': [], 'por-fecha': []}
    lock = threading.Lock()
    fin = time.monotonic() + DURACION_S
    arranque = threading.Barrier(len(USUARIOS))

    def usuario(i):
        tok = toks[USUARIOS[i]]
        arranque.wait(60)
        k = i
        while time.monotonic() < fin:
            esp = ESPECIALIDADES[k % 3]
            if k % 2 == 0:
                ruta = f"/api/agenda/cupos/proximos?especialidadId={esp}"
            else:
                ruta = f"/api/agenda/cupos?especialidadId={esp}&fecha={fechas[k % len(fechas)]}"
            r = c.peticion("GET", ruta, token=tok, timeout=10, reintentos_429=0)
            with lock:
                estados[r.status] += 1
                if r.status == 200:
                    lat.append(r.seg * 1000)
                    por_ruta['proximos' if 'proximos' in ruta else 'por-fecha'].append(r.seg * 1000)
                    cache[r.cabeceras.get("x-cache", "SIN-CABECERA").upper()] += 1
            k += 1
            time.sleep(PAUSA_S)

    with ThreadPoolExecutor(len(USUARIOS)) as ex:
        list(ex.map(usuario, range(len(USUARIOS))))

    p50, p95, pmax = c.percentil(lat, 50), c.percentil(lat, 95), max(lat, default=0)
    con_cabecera = cache["HIT"] + cache["MISS"]
    ratio = cache["HIT"] / con_cabecera if con_cabecera else None
    log(f"{len(lat)} busquedas ok en {DURACION_S} s ({len(lat) / DURACION_S:.0f}/s); estados {dict(estados)}")
    log(f"p50 {p50:.0f} ms, p95 {p95:.0f} ms, max {pmax:.0f} ms; X-Cache {dict(cache)}")
    for k, v in por_ruta.items():
        log(f"  {k}: n={len(v)} p50 {c.percentil(v, 50):.0f} ms, p95 {c.percentil(v, 95):.0f} ms")
    esc.nota("Corrida a escala reducida (50 usuarios, ~30 s), NO los 3.000 usuarios del escenario. "
             "El riesgo R2 pide la prueba de carga completa (3.000 usuarios sobre una sola agenda).")
    esc.mide("Busquedas exitosas (HTTP 200)", f"{len(lat)} de {sum(estados.values())} ({dict(estados)})",
             len(lat) >= 100 and len(lat) == sum(estados.values()))
    esc.mide("Proporcion de aciertos de cache (X-Cache HIT), informativo",
             f"{ratio:.0%} ({dict(cache)})" if ratio is not None else f"sin cabecera X-Cache ({dict(cache)})", True)
    esc.mide(f"Buscar cupos p95 sobre {len(lat)} busquedas (<= 500 ms)",
             f"{p95:.0f} ms (p50 {p50:.0f}, max {pmax:.0f})", len(lat) > 0 and p95 <= 500)


def ejecutar():
    return c.correr(escenario(), cuerpo)
