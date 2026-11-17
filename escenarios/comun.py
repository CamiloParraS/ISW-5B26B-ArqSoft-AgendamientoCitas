"""Utilidades comunes de los escenarios de MediCita (solo biblioteca estandar)."""
import json
import os
import ssl
import subprocess
import sys
import textwrap
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone  # noqa

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = os.environ.get("MEDICITA_BASE_URL", "https://gateway").rstrip("/")
URL_TOKEN = BASE + "/auth/realms/medicita/protocol/openid-connect/token"
SIMULADORES = os.environ.get("MEDICITA_SIMULADORES_URL", "http://simuladores:8090").rstrip("/")
NOTIFICACIONES = os.environ.get("MEDICITA_NOTIFICACIONES_URL", "http://notificaciones:8080").rstrip("/")
INTEGRACION = os.environ.get("MEDICITA_INTEGRACION_URL", "http://integracion-clinica:8080").rstrip("/")
BOGOTA = timezone(timedelta(hours=-5))

# Datos semilla compartidos (SPEC seccion 5)
ESP_MEDICINA_GENERAL = "f0000000-0000-4000-8000-000000000001"
ESP_PEDIATRIA = "f0000000-0000-4000-8000-000000000002"
ESP_CARDIOLOGIA = "f0000000-0000-4000-8000-000000000003"
MEDICO_ROSERO = "b0000000-0000-4000-8000-000000000001"
MEDICO_BURBANO = "b0000000-0000-4000-8000-000000000002"
MEDICO_ORTIZ = "b0000000-0000-4000-8000-000000000003"
ANA_ID = "a0000000-0000-4000-8000-000000000001"
ANA_DOCUMENTO = "1085000001"

_CTX = ssl._create_unverified_context()
_T0 = time.monotonic()


class ErrorEscenario(Exception):
    pass


# --------------------------------------------------------------------------- registro
def log(msg):
    print(f"[{time.monotonic() - _T0:7.1f}s] {msg}", flush=True)


def nuevo_id():
    return str(uuid.uuid4())


# --------------------------------------------------------------------------- HTTP
class Resp:
    def __init__(self, status, texto, cabeceras, seg, error, correlacion):
        self.status = status
        self.texto = texto or ""
        self.cabeceras = cabeceras or {}
        self.seg = seg
        self.error = error
        self.correlacion = correlacion
        try:
            self.datos = json.loads(self.texto) if self.texto.strip() else None
        except ValueError:
            self.datos = None

    @property
    def ok(self):
        return 200 <= self.status < 300

    @property
    def instancia(self):
        return self.cabeceras.get("x-instancia", "?")

    def codigo(self):
        return self.datos.get("codigo") if isinstance(self.datos, dict) else None

    def resumen(self):
        if self.status == 0:
            return f"sin respuesta ({self.error})"
        return f"HTTP {self.status} {self.texto[:160]!r}"


def peticion(metodo, destino, token=None, cuerpo=None, cabeceras=None, timeout=10.0,
             correlacion=None, form=None, reintentos_429=6):
    """Peticion HTTP. Nunca lanza: devuelve Resp con status 0 ante error de red.
    Reintenta solo los 429 (limite de tasa) con una espera corta."""
    url = destino if destino.startswith("http") else BASE + destino
    correlacion = correlacion or nuevo_id()
    h = {"Accept": "application/json", "X-Correlation-Id": correlacion}
    if token:
        h["Authorization"] = "Bearer " + token
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    elif cuerpo is not None:
        data = json.dumps(cuerpo).encode()
        h["Content-Type"] = "application/json"
    elif metodo in ("POST", "PUT", "PATCH"):
        data = b""
    h.update(cabeceras or {})
    for intento in range(reintentos_429 + 1):
        t0 = time.monotonic()
        try:
            req = urllib.request.Request(url, data=data, headers=h, method=metodo)
            with urllib.request.urlopen(req, timeout=timeout, context=_CTX) as rs:
                raw = rs.read()
                status = rs.status
                hdrs = {k.lower(): v for k, v in rs.headers.items()}
        except urllib.error.HTTPError as e:
            try:
                raw = e.read()
            except Exception:
                raw = b""
            status = e.code
            hdrs = {k.lower(): v for k, v in e.headers.items()}
        except Exception as e:  # URLError, timeout, reset...
            return Resp(0, "", {}, time.monotonic() - t0, f"{type(e).__name__}: {e}", correlacion)
        r = Resp(status, raw.decode("utf-8", "replace"), hdrs, time.monotonic() - t0, None, correlacion)
        if status == 429 and intento < reintentos_429:
            time.sleep(0.5 + 0.2 * intento)
            continue
        return r
    return r


_tokens = {}
_tokens_lock = threading.Lock()


def token(usuario, clave="medicita"):
    """Token de Keycloak por 'direct grant' (cliente escenarios), con cache."""
    with _tokens_lock:
        t = _tokens.get(usuario)
        if t and t[1] > time.time() + 30:
            return t[0]
    ultimo = None
    for i in range(4):
        r = peticion("POST", URL_TOKEN, form={
            "grant_type": "password", "client_id": "escenarios",
            "username": usuario, "password": clave, "scope": "openid"}, timeout=15)
        if r.ok and isinstance(r.datos, dict) and "access_token" in r.datos:
            with _tokens_lock:
                _tokens[usuario] = (r.datos["access_token"], time.time() + int(r.datos.get("expires_in", 300)))
            return r.datos["access_token"]
        ultimo = r
        time.sleep(1 + i)
    raise ErrorEscenario(f"No se pudo obtener token de '{usuario}' en {URL_TOKEN}: {ultimo.resumen()}")


def tokens_en_paralelo(usuarios, hilos=10):
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(hilos) as ex:
        return dict(zip(usuarios, ex.map(token, usuarios)))


def ahora_bogota():
    return datetime.now(BOGOTA)


def a_bogota(iso):
    return datetime.fromisoformat(iso).astimezone(BOGOTA)


# --------------------------------------------------------------------------- API de negocio
def cupos_libres(tok, especialidad_id, medico_id, desde_dias, minimo, hasta_dias=20, antelacion_h=30):
    """Cupos libres futuros de un medico, recorriendo dias habiles desde hoy+desde_dias.
    Descarta los que empiezan en menos de 'antelacion_h' horas (cancelaciones sin penalidad).
    Si el rango no da nada, intenta dias anteriores y por ultimo /cupos/proximos."""
    limite = datetime.now(timezone.utc) + timedelta(hours=antelacion_h)
    hoy = ahora_bogota().date()
    encontrados, vistos = [], set()

    def dia(d):
        f = hoy + timedelta(days=d)
        if f.weekday() >= 5:
            return
        r = peticion("GET", f"/api/agenda/cupos?especialidadId={especialidad_id}&medicoId={medico_id}&fecha={f.isoformat()}",
                     token=tok)
        if not r.ok or not isinstance(r.datos, list):
            if r.status != 200:
                log(f"  aviso: consulta de cupos {f} -> {r.resumen()}")
            return
        for c in r.datos:
            if c["id"] not in vistos and datetime.fromisoformat(c["inicio"]) > limite:
                vistos.add(c["id"])
                encontrados.append(c)

    for d in list(range(desde_dias, hasta_dias + 1)) + list(range(2, desde_dias)):
        if len(encontrados) >= minimo:
            break
        dia(d)
    if not encontrados:
        r = peticion("GET", f"/api/agenda/cupos/proximos?especialidadId={especialidad_id}", token=tok)
        if r.ok and isinstance(r.datos, list):
            encontrados = [c for c in r.datos if datetime.fromisoformat(c["inicio"]) > limite
                           and c.get("medicoId") in (medico_id, None)]
    encontrados.sort(key=lambda c: c["inicio"])
    return encontrados


def retener(tok, cupo_id, **kw):
    return peticion("POST", "/api/agenda/retenciones", token=tok, cuerpo={"cupoId": cupo_id}, **kw)


def confirmar(tok, cupo_id, clave, **kw):
    return peticion("POST", "/api/agenda/citas", token=tok, cuerpo={"cupoId": cupo_id},
                    cabeceras={"Idempotency-Key": clave}, **kw)


def cancelar(tok, cita_id, **kw):
    return peticion("POST", f"/api/agenda/citas/{cita_id}/cancelacion", token=tok, **kw)


def mis_citas(tok, **kw):
    return peticion("GET", "/api/agenda/citas/mias", token=tok, **kw)


def reservar_simple(usuario, cupo, correlacion=None):
    """Retiene y confirma un cupo (camino feliz). Devuelve la cita (dict) o lanza ErrorEscenario."""
    tok = token(usuario)
    correlacion = correlacion or nuevo_id()
    r = retener(tok, cupo["id"], correlacion=correlacion, timeout=15)
    if r.status != 201 and r.status != 200:
        raise ErrorEscenario(f"retencion de {cupo['id']} por {usuario}: {r.resumen()}")
    r = confirmar(tok, cupo["id"], nuevo_id(), correlacion=correlacion, timeout=15)
    if not r.ok or not isinstance(r.datos, dict):
        raise ErrorEscenario(f"confirmacion de {cupo['id']} por {usuario}: {r.resumen()}")
    return r.datos


def cancelar_vigentes(usuario):
    """Cancela todas las citas CONFIRMADA futuras del usuario (limpieza de usuarios de prueba)."""
    try:
        tok = token(usuario)
        r = mis_citas(tok, timeout=15)
        if not r.ok or not isinstance(r.datos, list):
            return 0
        n = 0
        for c in r.datos:
            if c.get("estado") == "CONFIRMADA":
                if cancelar(tok, c["id"], timeout=15).ok:
                    n += 1
        return n
    except Exception as e:  # limpieza: nunca debe romper
        log(f"  limpieza de citas de {usuario}: {e}")
        return 0


# --------------------------------------------------------------------------- Docker
def docker(*args, timeout=180):
    try:
        p = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout,
                           encoding="utf-8", errors="replace")
        return p.returncode, p.stdout, p.stderr
    except FileNotFoundError:
        return 127, "", "CLI docker no encontrada"
    except subprocess.TimeoutExpired:
        return 124, "", f"docker {' '.join(args[:2])} excedio {timeout}s"


_proyecto = None


def _proyecto_compose():
    global _proyecto
    if _proyecto is None:
        _proyecto = ""
        host = os.environ.get("HOSTNAME", "")
        if host:
            rc, out, _ = docker("inspect", "-f", '{{index .Config.Labels "com.docker.compose.project"}}', host, timeout=15)
            if rc == 0 and out.strip() and out.strip() != "<no value>":
                _proyecto = out.strip()
    return _proyecto


def contenedor(servicio):
    """Nombre del contenedor de un servicio compose (por etiqueta, sin depender del prefijo)."""
    filtros = ["--filter", f"label=com.docker.compose.service={servicio}"]
    if _proyecto_compose():
        filtros += ["--filter", f"label=com.docker.compose.project={_proyecto_compose()}"]
    rc, out, err = docker("ps", "-a", *filtros, "--format", "{{.Names}}", timeout=30)
    nombres = [n for n in out.split() if n]
    if rc != 0 or not nombres:
        raise ErrorEscenario(f"No se encontro el contenedor del servicio '{servicio}' ({err.strip() or 'sin coincidencias'})")
    return nombres[0]


def estado_contenedor(servicio):
    nombre = contenedor(servicio)
    rc, out, _ = docker("inspect", "-f",
                        "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
                        nombre, timeout=30)
    return out.strip() if rc == 0 else "desconocido"


def detener(servicio, espera=10):
    nombre = contenedor(servicio)
    log(f"docker stop {nombre}")
    rc, _, err = docker("stop", "-t", str(espera), nombre, timeout=espera + 90)
    if rc != 0:
        raise ErrorEscenario(f"docker stop {nombre} fallo: {err.strip()}")


def iniciar(*servicios):
    nombres = [contenedor(s) for s in servicios]
    log(f"docker start {' '.join(nombres)}")
    rc, _, err = docker("start", *nombres, timeout=120)
    if rc != 0:
        raise ErrorEscenario(f"docker start {' '.join(nombres)} fallo: {err.strip()}")


def esperar_saludable(servicio, timeout=240):
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        try:
            e = estado_contenedor(servicio)
        except ErrorEscenario:
            e = "?"
        if e in ("healthy", "running"):
            log(f"{servicio}: {e} ({time.monotonic() - t0:.0f}s)")
            return True
        time.sleep(2)
    log(f"{servicio}: NO llego a healthy en {timeout}s")
    return False


def logs(servicio, desde="15m", cola=5000):
    rc, out, err = docker("logs", "--since", desde, "--tail", str(cola), contenedor(servicio), timeout=60)
    return out + err


# --------------------------------------------------------------------------- PostgreSQL / Patroni
def lider_patroni():
    for n in (1, 2):
        r = peticion("GET", f"http://bd-agendamiento-{n}:8008/leader", timeout=3, reintentos_429=0)
        if r.status == 200:
            return f"bd-agendamiento-{n}"
    return None


_ERR_CONEXION = ("could not connect", "No such file", "the database system is", "connection refused",
                 "server closed the connection", "not accepting connections", "No such container",
                 "is not running", "Connection refused")


def psql(sql, reintentar=True, espera_lider=90):
    """Ejecuta SQL en el lider Patroni via 'docker exec ... psql'. Devuelve (rc, stdout, stderr)."""
    limite = time.monotonic() + espera_lider
    rc, out, err = 1, "", "sin lider de Patroni"
    while True:
        lider = lider_patroni()
        if lider:
            cont = contenedor(lider)
            base = ["exec", "-e", "PGPASSWORD=medicita", cont, "psql", "-U", "medicita", "-d", "medicita",
                    "-v", "ON_ERROR_STOP=1", "-At", "-F", "|", "-c", sql]
            rc, out, err = docker(*base, timeout=60)
            if rc != 0 and "No such file" in err:  # socket no estandar: probar por TCP local
                base2 = base[:5] + ["-h", "127.0.0.1"] + base[5:]
                rc, out, err = docker(*base2, timeout=60)
            if rc == 0 or not reintentar or not any(m in err for m in _ERR_CONEXION):
                return rc, out, err
        if not reintentar or time.monotonic() > limite:
            return rc, out, err
        time.sleep(2)


def psql_filas(sql, **kw):
    rc, out, err = psql(sql, **kw)
    if rc != 0:
        raise ErrorEscenario(f"psql fallo: {err.strip()[:300]}")
    return [l.split("|") for l in out.splitlines() if l.strip()]


# --------------------------------------------------------------------------- varios
def percentil(valores, p):
    if not valores:
        return 0.0
    v = sorted(valores)
    k = max(0, min(len(v) - 1, int(-(-p / 100 * len(v) // 1)) - 1))
    return v[k]


def esperar_hasta(fn, timeout, intervalo=2.0):
    """Llama fn() hasta que devuelva algo verdadero. Devuelve (valor, segundos) o (None, segundos)."""
    t0 = time.monotonic()
    while True:
        try:
            v = fn()
        except Exception:
            v = None
        if v:
            return v, time.monotonic() - t0
        if time.monotonic() - t0 >= timeout:
            return None, time.monotonic() - t0
        time.sleep(intervalo)


def lista_de(datos):
    """Acepta una lista JSON o un objeto con una lista dentro (p.ej. {"envios":[...]})."""
    if isinstance(datos, list):
        return datos
    if isinstance(datos, dict):
        for v in datos.values():
            if isinstance(v, list):
                return v
    return []


def comprobar_pila():
    rc, _, err = docker("version", "--format", "{{.Server.Version}}", timeout=20)
    if rc != 0:
        raise ErrorEscenario(f"No hay acceso a Docker ({err.strip()}). Ejecute con el perfil compose 'escenarios' "
                             "(monta /var/run/docker.sock): docker compose run --rm escenarios <nombre>")
    token("ana")


class Limpieza:
    """Acciones de restauracion que siempre se ejecutan (LIFO), incluso si el escenario falla."""

    def __init__(self):
        self._acciones = []

    def agregar(self, descripcion, fn):
        self._acciones.append((descripcion, fn))

    def ejecutar(self):
        if not self._acciones:
            return
        log("--- restaurando estado ---")
        while self._acciones:
            desc, fn = self._acciones.pop()
            try:
                log(f"limpieza: {desc}")
                fn()
            except Exception as e:
                log(f"  la limpieza '{desc}' fallo: {type(e).__name__}: {e}")


# --------------------------------------------------------------------------- presentacion
ANCHO = 100


class Escenario:
    def __init__(self, nombre, titulo, fuente, estimulo, artefacto, entorno, respuesta, medida):
        self.nombre = nombre
        self.titulo = titulo
        self.partes = [("Fuente", fuente), ("Estimulo", estimulo), ("Artefacto", artefacto),
                       ("Entorno", entorno), ("Respuesta", respuesta), ("Medida", medida)]
        self.mediciones = []
        self.notas = []
        self.error = None

    def mide(self, descripcion, observado, cumple):
        self.mediciones.append((descripcion, str(observado), bool(cumple)))
        log(f"medida: {descripcion} -> {observado} [{'PASA' if cumple else 'NO PASA'}]")

    def nota(self, texto):
        self.notas.append(texto)

    @property
    def pasa(self):
        return self.error is None and bool(self.mediciones) and all(m[2] for m in self.mediciones)

    def imprimir_cabecera(self):
        print("=" * ANCHO)
        print(f"ESCENARIO {self.nombre.upper()} - {self.titulo}")
        print("=" * ANCHO)
        for k, v in self.partes:
            lineas = textwrap.wrap(v, ANCHO - 14) or [""]
            print(f"  {k:<10}| {lineas[0]}")
            for l in lineas[1:]:
                print(f"  {'':<10}| {l}")
        print("-" * ANCHO, flush=True)

    def imprimir_resultado(self):
        print("-" * ANCHO)
        print(f"RESULTADO {self.nombre.upper()}")
        for d, obs, ok in self.mediciones:
            print(f"  [{'PASA   ' if ok else 'NO PASA'}] {d}: {obs}")
        for n in self.notas:
            print(f"  nota: {n}")
        if self.error:
            print(f"  ERROR DE EJECUCION: {self.error.splitlines()[-1] if self.error else ''}")
        print(f"  VEREDICTO {self.nombre.upper()}: {'PASA' if self.pasa else 'NO PASA'}")
        print("=" * ANCHO, flush=True)


def correr(esc, cuerpo):
    """Ejecuta cuerpo(esc, limpieza) con restauracion garantizada. Devuelve 0 si PASA, 1 si no."""
    esc.imprimir_cabecera()
    limp = Limpieza()
    try:
        comprobar_pila()
        cuerpo(esc, limp)
    except KeyboardInterrupt:
        esc.error = "interrumpido por el usuario"
    except Exception as e:
        esc.error = f"{type(e).__name__}: {e}"
        traceback.print_exc()
    finally:
        try:
            limp.ejecutar()
        except BaseException as e:  # incluso Ctrl+C durante la limpieza
            log(f"limpieza interrumpida: {e}")
            limp.ejecutar()
    esc.imprimir_resultado()
    return 0 if esc.pasa else 1
