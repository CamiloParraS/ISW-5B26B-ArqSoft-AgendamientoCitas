"""Utilidades compartidas por los scripts de escenarios (solo biblioteca estándar)."""
import json
import math
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CLAVE_DEMO = "demo1234"


class Cliente:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/")
        self.token = token

    def pedir(self, metodo, ruta, cuerpo=None, cabeceras=None, timeout=15):
        datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
        h = {"Content-Type": "application/json", **(cabeceras or {})}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        peticion = urllib.request.Request(self.base + ruta, data=datos, method=metodo, headers=h)
        try:
            with urllib.request.urlopen(peticion, timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def ingresar(self, usuario):
        estado, r = self.pedir("POST", "/api/identidad/sesiones", {"usuario": usuario, "clave": CLAVE_DEMO})
        if estado != 201:
            raise SystemExit(f"No se pudo ingresar como {usuario}: {estado} {r}")
        return Cliente(self.base, r["token"]), r["usuario"]


def percentil(valores, p):
    ordenados = sorted(valores)
    if not ordenados:
        return float("nan")
    k = max(0, math.ceil(p / 100 * len(ordenados)) - 1)
    return ordenados[k]


def compose(*args, capturar=True):
    resultado = subprocess.run(["docker", "compose", *args], cwd=RAIZ, capture_output=capturar, text=True, encoding="utf-8")
    if resultado.returncode != 0:
        raise SystemExit(f"docker compose {' '.join(args)} falló:\n{resultado.stderr}")
    return resultado.stdout if capturar else ""


def esperar_salud(base, limite_s=180):
    inicio = time.monotonic()
    while time.monotonic() - inicio < limite_s:
        try:
            with urllib.request.urlopen(base + "/salud", timeout=3) as r:
                if r.status == 200:
                    return time.monotonic() - inicio
        except Exception:  # noqa: BLE001
            pass
        time.sleep(1)
    return None
