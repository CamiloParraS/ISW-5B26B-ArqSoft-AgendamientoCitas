"""Punto de entrada: docker compose run --rm escenarios <nombre>"""
import importlib
import sys
import time

ESCENARIOS = {
    "e1": ("e1", "Disponibilidad: cae una instancia de la API y la BD primaria (<= 60 s, 0 citas perdidas)"),
    "e2": ("e2", "Escalabilidad: 50 pacientes por el mismo cupo (1 ganador, 0 dobles, p95 <= 800 ms)"),
    "e3": ("e3", "Seguridad: consulta de IPS aliada (4 campos, 100 % en bitacora inmutable)"),
    "hce-caida": ("hce_caida", "HCE caida: la cita se agenda y llega a la HCE <= 15 min despues"),
    "respaldo-canal": ("respaldo_canal", "Plantilla de WhatsApp rechazada: respaldo a SMS <= 2 min"),
    "broker-caido": ("broker_caido", "Broker caido: la reserva sigue y la outbox se drena al volver"),
    "busqueda": ("busqueda", "Busqueda de cupos con 50 usuarios concurrentes (p95 <= 500 ms, escala reducida)"),
    "traza": ("traza", "Traza correlacionada: un X-Correlation-Id de la app a WhatsApp"),
}


def uso():
    print("Escenarios de calidad de MediCita\n")
    print("Uso: docker compose run --rm escenarios <nombre>\n")
    for k, (_, d) in ESCENARIOS.items():
        print(f"  {k:<15} {d}")
    print(f"  {'todos':<15} ejecuta todos en secuencia (el codigo de salida es 0 solo si todos PASAN)")


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help", "help"):
        uso()
        return 0
    nombre = argv[1].lower()
    if nombre == "todos":
        resultados = {}
        for k, (modulo, _) in ESCENARIOS.items():
            t0 = time.monotonic()
            resultados[k] = (importlib.import_module(modulo).ejecutar(), time.monotonic() - t0)
        print("\n" + "=" * 100)
        print("RESUMEN")
        for k, (rc, seg) in resultados.items():
            print(f"  {k:<15} {'PASA' if rc == 0 else 'NO PASA':<8} {seg:6.0f} s")
        ok = all(rc == 0 for rc, _ in resultados.values())
        print(f"VEREDICTO GLOBAL: {'PASA' if ok else 'NO PASA'}")
        return 0 if ok else 1
    if nombre not in ESCENARIOS:
        print(f"Escenario desconocido: {nombre}\n", file=sys.stderr)
        uso()
        return 2
    return importlib.import_module(ESCENARIOS[nombre][0]).ejecutar()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
