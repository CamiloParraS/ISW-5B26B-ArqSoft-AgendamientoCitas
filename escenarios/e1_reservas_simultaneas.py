"""E-1 · Reservas simultáneas sobre un cupo (C-1).

Estímulo: 30 solicitudes sobre el mismo cupo en el mismo segundo.
Medida:   0 dobles reservas en 100 repeticiones; p95 ≤ 1 s.

Uso (con el sistema arriba):  python escenarios/e1_reservas_simultaneas.py
"""
import argparse
import random
import threading
import time
import uuid
from datetime import date, timedelta

from comun import Cliente, percentil


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--solicitudes", type=int, default=30)
    p.add_argument("--repeticiones", type=int, default=100)
    a = p.parse_args()

    anonimo = Cliente(a.base)
    recepcion, _ = anonimo.ingresar("recepcion")
    pacientes = [anonimo.ingresar(u)[0] for u in ("ana", "bruno")]
    _, medicos = recepcion.pedir("GET", "/api/identidad/medicos")
    medico_id = medicos["medicos"][0]["id"]

    # Un día lejano y propio de esta corrida, con un cupo por repetición.
    dia = date.today() + timedelta(days=random.randint(400, 4000))
    estado, r = recepcion.pedir("POST", "/api/agenda/horarios", {
        "medico_id": medico_id, "fecha": dia.isoformat(), "hora_inicio": "00:00", "hora_fin": "23:55", "duracion_minutos": 5,
    })
    _, libres = recepcion.pedir("GET", f"/api/agenda/cupos?medico_id={medico_id}&desde={dia}&hasta={dia}")
    cupos = libres["cupos"][: a.repeticiones]
    if len(cupos) < a.repeticiones:
        raise SystemExit("No hay suficientes cupos libres para las repeticiones pedidas")

    latencias, dobles, sin_confirmar = [], 0, 0
    for i, cupo in enumerate(cupos, 1):
        barrera = threading.Barrier(a.solicitudes)
        resultados = []
        candado = threading.Lock()

        def solicitar(n):
            cliente = pacientes[n % len(pacientes)]
            barrera.wait()
            t0 = time.perf_counter()
            estado, _ = cliente.pedir("POST", "/api/agenda/citas", {"cupo_id": cupo["id"]}, {"Idempotency-Key": uuid.uuid4().hex})
            with candado:
                resultados.append(estado)
                latencias.append(time.perf_counter() - t0)

        hilos = [threading.Thread(target=solicitar, args=(n,)) for n in range(a.solicitudes)]
        for h in hilos:
            h.start()
        for h in hilos:
            h.join()

        confirmadas = resultados.count(201)
        rechazadas = resultados.count(409)
        _, citas = recepcion.pedir("GET", f"/api/agenda/citas?medico_id={medico_id}&desde={dia}&hasta={dia}")
        vigentes = sum(1 for c in citas["citas"] if c["cupo_id"] == cupo["id"] and c["estado"] != "CANCELADA")
        dobles += max(0, vigentes - 1)
        sin_confirmar += confirmadas == 0
        print(f"repetición {i:3d}: confirmadas={confirmadas} 'cupo ya tomado'={rechazadas} otras={len(resultados) - confirmadas - rechazadas} "
              f"citas vigentes en el cupo={vigentes}")

    p95 = percentil(latencias, 95)
    print("\n=== E-1 · Resultado ===")
    print(f"Solicitudes: {len(latencias)} ({a.solicitudes} simultáneas × {a.repeticiones} repeticiones)")
    print(f"Dobles reservas: {dobles}   (medida: 0)")
    print(f"Repeticiones sin ninguna confirmada: {sin_confirmar}")
    print(f"Latencia p50={percentil(latencias, 50):.3f} s  p95={p95:.3f} s  máx={max(latencias):.3f} s   (medida: p95 ≤ 1 s)")
    cumple = dobles == 0 and sin_confirmar == 0 and p95 <= 1.0
    print("CUMPLE" if cumple else "NO CUMPLE")
    raise SystemExit(0 if cumple else 1)


if __name__ == "__main__":
    main()
