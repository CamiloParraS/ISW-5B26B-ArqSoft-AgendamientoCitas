"""Chequeo de salud que usa la plataforma (táctica "Latido y reinicio", escenario E-3).

Docker lo ejecuta cada 10 s dentro del contenedor. Si /salud no responde 3 veces seguidas,
termina el proceso maestro de gunicorn (SIGKILL funciona aunque esté congelado); el init del
contenedor sale con él y la política "restart: always" lo reinicia.
Así la recuperación queda en ≤ 2 min sin intervención humana.
"""
import os
import signal
import sys
import time
import urllib.request

FALLOS_PARA_REINICIAR = 3
GRACIA_ARRANQUE_S = 90
ARCHIVO_FALLOS = "/tmp/fallos_salud"


def _leer_entero(ruta, defecto=0):
    try:
        with open(ruta) as f:
            return int(f.read().strip() or defecto)
    except (OSError, ValueError):
        return defecto


def main():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8000/salud", timeout=5) as r:
            if r.status != 200:
                raise RuntimeError(f"estado {r.status}")
    except Exception as error:  # noqa: BLE001 - cualquier falla cuenta como latido perdido
        fallos = _leer_entero(ARCHIVO_FALLOS) + 1
        with open(ARCHIVO_FALLOS, "w") as f:
            f.write(str(fallos))
        en_gracia = time.time() - _leer_entero("/tmp/arranque", int(time.time())) < GRACIA_ARRANQUE_S
        print(f"/salud no responde ({error}); fallo {fallos}/{FALLOS_PARA_REINICIAR}", file=sys.stderr)
        if fallos >= FALLOS_PARA_REINICIAR and not en_gracia:
            print("Reiniciando el proceso de la aplicación", file=sys.stderr)
            os.kill(_leer_entero("/tmp/gunicorn.pid", 1), signal.SIGKILL)
        return 1
    with open(ARCHIVO_FALLOS, "w") as f:
        f.write("0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
