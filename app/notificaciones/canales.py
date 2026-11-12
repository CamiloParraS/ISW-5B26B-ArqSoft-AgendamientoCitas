"""Infraestructura: canales de envío hacia el Proveedor de correo/SMS [HTTPS/API].

M-1: pasar los recordatorios a WhatsApp = agregar un canal en este archivo y cambiar
NOTIFICACIONES_CANAL. Agenda no se toca.
"""
import json
import urllib.error
import urllib.request

from django.conf import settings


class ErrorDeCanal(Exception):
    pass


class CanalProveedor:
    def __init__(self, nombre, url_base, timeout):
        self.nombre = nombre
        self.url = url_base.rstrip("/") + "/mensajes"
        self.timeout = timeout

    def enviar(self, destino, mensaje):
        datos = json.dumps({"canal": self.nombre, "destino": destino, "mensaje": mensaje}).encode("utf-8")
        peticion = urllib.request.Request(self.url, data=datos, method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(peticion, timeout=self.timeout) as r:
                if r.status >= 300:
                    raise ErrorDeCanal(f"El proveedor respondió {r.status}")
        except (urllib.error.URLError, OSError) as e:
            raise ErrorDeCanal(f"Proveedor no disponible: {e}") from e


CANALES = {
    "correo": lambda: CanalProveedor("correo", settings.PROVEEDOR_MENSAJERIA_URL, settings.PROVEEDOR_TIMEOUT_S),
    "sms": lambda: CanalProveedor("sms", settings.PROVEEDOR_MENSAJERIA_URL, settings.PROVEEDOR_TIMEOUT_S),
}


def obtener_canal(nombre=None):
    return CANALES[nombre or settings.NOTIFICACIONES_CANAL]()
