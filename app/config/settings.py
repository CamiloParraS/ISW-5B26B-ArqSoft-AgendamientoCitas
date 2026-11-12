"""Configuración del proceso único de la Aplicación de agenda (monolito modular)."""
import os
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
EN_PRUEBAS = len(sys.argv) > 1 and sys.argv[1] == "test"


def entorno(nombre, defecto=None):
    return os.environ.get(nombre, defecto)


SECRET_KEY = entorno("DJANGO_SECRET_KEY", "solo-para-desarrollo-cambiar-en-produccion")
DEBUG = entorno("DJANGO_DEBUG", "0") == "1"
ALLOWED_HOSTS = entorno("DJANGO_ALLOWED_HOSTS", "*").split(",")

# "config" es la raíz de composición (salud, barrido, siembra). Los cuatro módulos del monolito:
INSTALLED_APPS = [
    "config",
    "identidad",
    "agenda",
    "atencion_clinica",
    "notificaciones",
]

# Sin cookies de sesión: la API usa un token Bearer emitido por Identidad, por eso no hay CSRF.
MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.middleware.common.CommonMiddleware",
]

ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
TEMPLATES = []
APPEND_SLASH = False

# PostgreSQL con un esquema por módulo (agenda, clinico, notificaciones, identidad), conexión SQL/TLS.
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": entorno("POSTGRES_DB", "agenda_citas"),
        "USER": entorno("POSTGRES_USER", "agenda"),
        "PASSWORD": entorno("POSTGRES_PASSWORD", ""),
        "HOST": entorno("POSTGRES_HOST", "localhost"),
        "PORT": entorno("POSTGRES_PORT", "5432"),
        "CONN_MAX_AGE": 60,
        "CONN_HEALTH_CHECKS": True,
        "OPTIONS": {"sslmode": entorno("POSTGRES_SSLMODE", "require")},
    }
}

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
LANGUAGE_CODE = "es-co"
TIME_ZONE = "America/Bogota"
USE_I18N = False
USE_TZ = True

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"consola": {"class": "logging.StreamHandler"}},
    "root": {"handlers": ["consola"], "level": "INFO"},
}

# Identidad y acceso
SESION_HORAS = 12
PASSWORD_HASHERS = (
    ["django.contrib.auth.hashers.MD5PasswordHasher"]  # solo acelera las pruebas
    if EN_PRUEBAS
    else ["django.contrib.auth.hashers.PBKDF2PasswordHasher"]
)

# Atención clínica: clave Fernet para cifrar en reposo diagnóstico y receta
CLAVE_CIFRADO_CLINICO = entorno("CLAVE_CIFRADO_CLINICO", "9xv36gRm8dJ9TOj3TgOgNplQw5g2lBLtdI7TYEdftuE=")

# Notificaciones
PROVEEDOR_MENSAJERIA_URL = "http://127.0.0.1:9" if EN_PRUEBAS else entorno("PROVEEDOR_MENSAJERIA_URL", "http://proveedor-correo-sms:9000")
PROVEEDOR_TIMEOUT_S = 3
NOTIFICACIONES_CANAL = entorno("NOTIFICACIONES_CANAL", "correo")
NOTIFICACIONES_ENVIO_INMEDIATO = (not EN_PRUEBAS) and entorno("NOTIFICACIONES_ENVIO_INMEDIATO", "1") == "1"

# Riesgo RS-3: alerta si el último barrido tiene más de 15 min
BARRIDO_ALERTA_MINUTOS = 15
