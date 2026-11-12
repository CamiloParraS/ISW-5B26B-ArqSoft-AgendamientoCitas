"""Proveedor de correo/SMS SIMULADO (sistema externo, fuera de la frontera del sistema).

Recibe POST /mensajes con {"canal", "destino", "mensaje"} y los guarda en memoria.
GET /mensajes devuelve la lista en JSON; GET / la muestra en una página que se refresca sola.
Para probar D-2 ("si cae el proveedor, se sigue agendando"): docker compose stop proveedor-correo-sms
"""
import html
import json
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MENSAJES = []
CANDADO = threading.Lock()


class Manejador(BaseHTTPRequestHandler):
    def _responder(self, estado, cuerpo, tipo="application/json; charset=utf-8"):
        datos = cuerpo.encode("utf-8")
        self.send_response(estado)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)

    def do_POST(self):
        if self.path != "/mensajes":
            return self._responder(404, '{"error": "no existe"}')
        largo = int(self.headers.get("Content-Length", 0))
        try:
            mensaje = json.loads(self.rfile.read(largo) or b"{}")
        except json.JSONDecodeError:
            return self._responder(400, '{"error": "JSON inválido"}')
        mensaje["recibido"] = datetime.now().isoformat(timespec="seconds")
        with CANDADO:
            MENSAJES.append(mensaje)
            numero = len(MENSAJES)
        print(f"[proveedor] #{numero} {mensaje.get('canal')} -> {mensaje.get('destino')}: {mensaje.get('mensaje')}", flush=True)
        self._responder(202, json.dumps({"id": numero}))

    def do_GET(self):
        with CANDADO:
            copia = list(MENSAJES)
        if self.path == "/mensajes":
            return self._responder(200, json.dumps(copia, ensure_ascii=False))
        filas = "".join(
            f"<tr><td>{i}</td><td>{html.escape(str(m.get('recibido')))}</td><td>{html.escape(str(m.get('canal')))}</td>"
            f"<td>{html.escape(str(m.get('destino')))}</td><td>{html.escape(str(m.get('mensaje')))}</td></tr>"
            for i, m in reversed(list(enumerate(copia, 1)))
        )
        pagina = f"""<!doctype html><html lang="es"><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>Proveedor simulado</title><style>body{{font-family:system-ui;margin:24px;color:#1f2937}}table{{border-collapse:collapse;width:100%}}
td,th{{border-bottom:1px solid #e5e7eb;padding:6px 8px;text-align:left;font-size:14px}}</style></head><body>
<h1>Proveedor de correo/SMS (simulado)</h1><p>Sistema externo. Nunca recibe datos clínicos. {len(copia)} mensajes recibidos.</p>
<table><tr><th>#</th><th>Recibido</th><th>Canal</th><th>Destino</th><th>Mensaje</th></tr>{filas}</table></body></html>"""
        self._responder(200, pagina, "text/html; charset=utf-8")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("[proveedor] escuchando en :9000", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 9000), Manejador).serve_forever()
