"""P-1 / Riesgo RS-2 · Consultar disponibilidad en p95 ≤ 1 s con 80 usuarios (lunes 7:00).

Uso:  python escenarios/p1_carga.py [--usuarios 80] [--segundos 30]
"""
import argparse
import random
import threading
import time

from comun import Cliente, percentil


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--usuarios", type=int, default=80)
    p.add_argument("--segundos", type=int, default=30)
    a = p.parse_args()

    anonimo = Cliente(a.base)
    sesiones = [anonimo.ingresar(u)[0] for u in ("ana", "bruno")]
    _, r = sesiones[0].pedir("GET", "/api/identidad/medicos")
    medicos = [m["id"] for m in r["medicos"]]

    latencias, errores = [], 0
    candado = threading.Lock()
    fin = time.monotonic() + a.segundos

    def usuario_virtual(n):
        nonlocal errores
        cliente = sesiones[n % len(sesiones)]
        while time.monotonic() < fin:
            t0 = time.perf_counter()
            estado, _ = cliente.pedir("GET", f"/api/agenda/cupos?medico_id={random.choice(medicos)}")
            with candado:
                latencias.append(time.perf_counter() - t0)
                errores += estado != 200
            time.sleep(random.uniform(0.2, 1.0))  # tiempo de lectura del usuario

    hilos = [threading.Thread(target=usuario_virtual, args=(n,)) for n in range(a.usuarios)]
    for h in hilos:
        h.start()
    for h in hilos:
        h.join()

    p95 = percentil(latencias, 95)
    print("=== P-1 · Resultado ===")
    print(f"{a.usuarios} usuarios virtuales durante {a.segundos} s: {len(latencias)} consultas, {errores} errores")
    print(f"Latencia p50={percentil(latencias, 50):.3f} s  p95={p95:.3f} s  máx={max(latencias):.3f} s   (medida: p95 ≤ 1 s)")
    cumple = p95 <= 1.0 and errores == 0
    print("CUMPLE" if cumple else "NO CUMPLE")
    raise SystemExit(0 if cumple else 1)


if __name__ == "__main__":
    main()
