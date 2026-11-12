"""E-3 · Caída del proceso en horario (D-1).

Estímulo: el proceso de la aplicación termina (o deja de responder).
Respuesta: la plataforma detecta que /salud no responde y reinicia el proceso.
Medida:   recuperación en ≤ 2 min; 0 citas confirmadas perdidas.

Uso:  python escenarios/e3_caida.py            (el proceso termina)
      python escenarios/e3_caida.py --congelar (el proceso deja de responder: lo detecta el latido)
"""
import argparse
import time

from comun import Cliente, compose, esperar_salud

CONGELAR = (
    "import os,signal\n"
    "for p in os.listdir('/proc'):\n"
    "    if p.isdigit() and int(p) != os.getpid() and b'gunicorn' in open(f'/proc/{p}/cmdline','rb').read():\n"
    "        os.kill(int(p), signal.SIGSTOP)\n"
)


def confirmadas(recepcion):
    estado, r = recepcion.pedir("GET", "/api/agenda/citas")
    return {c["id"] for c in r["citas"] if c["estado"] == "CONFIRMADA"}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--congelar", action="store_true", help="congela el proceso en vez de terminarlo")
    a = p.parse_args()

    recepcion, _ = Cliente(a.base).ingresar("recepcion")
    antes = confirmadas(recepcion)
    print(f"Citas confirmadas antes de la caída: {len(antes)}")

    if a.congelar:
        print("Congelando el proceso (deja de responder; el latido /salud debe detectarlo)…")
        compose("exec", "-T", "aplicacion-agenda", "python", "-c", CONGELAR)
    else:
        print("Terminando el proceso de la aplicación (SIGKILL al maestro de gunicorn)…")
        compose("exec", "-T", "aplicacion-agenda", "sh", "-c", "kill -KILL $(cat /tmp/gunicorn.pid)")

    inicio = time.monotonic()
    time.sleep(2)
    tiempo = esperar_salud(a.base, limite_s=300)
    if tiempo is None:
        raise SystemExit("La aplicación no se recuperó en 5 min. NO CUMPLE")
    recuperacion = time.monotonic() - inicio

    recepcion, _ = Cliente(a.base).ingresar("recepcion")  # las sesiones viven en la base: siguen válidas, pero entramos de nuevo
    despues = confirmadas(recepcion)
    perdidas = antes - despues
    print("\n=== E-3 · Resultado ===")
    print(f"Recuperación: {recuperacion:.0f} s   (medida: ≤ 120 s)")
    print(f"Citas confirmadas perdidas: {len(perdidas)}   (medida: 0)")
    cumple = recuperacion <= 120 and not perdidas
    print("CUMPLE" if cumple else "NO CUMPLE")
    raise SystemExit(0 if cumple else 1)


if __name__ == "__main__":
    main()
