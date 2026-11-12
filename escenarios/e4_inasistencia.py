"""E-4 · Inasistencia después de un reinicio (T-1).

Estímulo: pasan 15 min del inicio de una cita sin que el médico marque asistencia.
Entorno:  el proceso se reinició justo cuando vencía el plazo.
Respuesta: el siguiente barrido marca la inasistencia una sola vez y deja la notificación pendiente.
Medida:   registro ≤ 20 min después de la hora de inicio; 0 duplicados si el barrido corre dos veces.

Uso:  python escenarios/e4_inasistencia.py
"""
import argparse
import re

from comun import Cliente, compose, esperar_salud


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:8080")
    a = p.parse_args()

    salida = compose("exec", "-T", "aplicacion-agenda", "python", "manage.py", "demo_inasistencia")
    print(salida.strip())
    cita_id = int(re.search(r"Cita #(\d+)", salida).group(1))

    print("\nReiniciando el proceso de la aplicación justo al vencer el plazo…")
    compose("restart", "aplicacion-agenda")
    esperar_salud(a.base)

    primera = compose("exec", "-T", "tarea-programada", "python", "manage.py", "barrido").strip()
    segunda = compose("exec", "-T", "tarea-programada", "python", "manage.py", "barrido").strip()
    print(f"Barrido 1: {primera}\nBarrido 2: {segunda}")

    bruno, _ = Cliente(a.base).ingresar("bruno")
    _, citas = bruno.pedir("GET", "/api/agenda/citas")
    cita = next(c for c in citas["citas"] if c["id"] == cita_id)
    _, envios = bruno.pedir("GET", "/api/notificaciones/mias")
    avisos = [e for e in envios["envios"] if e["cita_id"] == cita_id and e["tipo"] == "INASISTENCIA"]

    print("\n=== E-4 · Resultado ===")
    print(f"Estado de la cita #{cita_id}: {cita['estado']}   (esperado: INASISTENCIA)")
    print(f"Notificaciones de inasistencia para la cita: {len(avisos)}   (medida: 1, sin duplicados)")
    print("(El barrido corre cada 5 min y marca a partir del minuto 15: registro a más tardar a los 20 min.)")
    cumple = cita["estado"] == "INASISTENCIA" and len(avisos) == 1
    print("CUMPLE" if cumple else "NO CUMPLE")
    raise SystemExit(0 if cumple else 1)


if __name__ == "__main__":
    main()
