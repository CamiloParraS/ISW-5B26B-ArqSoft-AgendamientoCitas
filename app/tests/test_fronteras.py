"""Función de aptitud de ADR-001: las fronteras del monolito modular.

1. Un módulo solo puede importar de otro su interfaz pública (<modulo>.api). Falla si importa sus modelos.
2. Ningún módulo escribe SQL contra el esquema de otro.
3. La raíz de composición (config) también usa solo las interfaces públicas.
4. Capas dentro de cada módulo: presentación -> aplicación -> dominio / infraestructura, nunca al revés.
"""
import ast
import re
from pathlib import Path

from django.test import SimpleTestCase

RAIZ = Path(__file__).resolve().parent.parent
MODULOS = {"identidad": "identidad", "agenda": "agenda", "atencion_clinica": "clinico", "notificaciones": "notificaciones"}

# Qué capa puede importar a cuál dentro del mismo módulo.
CAPAS_PERMITIDAS = {
    "dominio": set(),
    "models": {"dominio"},
    "canales": {"dominio"},
    "cifrado": {"dominio"},
    "aplicacion": {"dominio", "models", "canales", "cifrado"},
    "api": {"dominio", "aplicacion"},
    "vistas": {"dominio", "aplicacion", "api"},
    "urls": {"vistas"},
    "apps": {"aplicacion", "api"},
    "management": {"dominio", "aplicacion", "api"},
}
# Capas que no pueden depender de ningún otro módulo.
CAPAS_AISLADAS = {"dominio", "models", "canales", "cifrado"}


def _archivos(carpeta):
    for ruta in sorted((RAIZ / carpeta).rglob("*.py")):
        if "migrations" in ruta.parts or ruta.name == "__init__.py":
            continue
        yield ruta


def _capa(modulo, ruta):
    partes = ruta.relative_to(RAIZ / modulo).parts
    return "management" if partes[0] == "management" else ruta.stem


def _importaciones(ruta, paquete):
    """Devuelve (modulo_superior, submodulo, linea) por cada importación."""
    arbol = ast.parse(ruta.read_text(encoding="utf-8"))
    for nodo in ast.walk(arbol):
        if isinstance(nodo, ast.Import):
            for alias in nodo.names:
                partes = alias.name.split(".")
                yield partes[0], partes[1] if len(partes) > 1 else None, nodo.lineno
        elif isinstance(nodo, ast.ImportFrom):
            if nodo.level:  # relativa: siempre dentro del mismo paquete superior
                destino = [paquete] + (nodo.module.split(".") if nodo.module else [])
            else:
                destino = nodo.module.split(".")
            if len(destino) > 1:
                yield destino[0], destino[1], nodo.lineno
            else:
                for alias in nodo.names:
                    yield destino[0], alias.name, nodo.lineno


class FronterasEntreModulos(SimpleTestCase):
    def test_un_modulo_solo_importa_la_api_publica_de_otro(self):
        violaciones = []
        for modulo in MODULOS:
            for ruta in _archivos(modulo):
                for superior, sub, linea in _importaciones(ruta, modulo):
                    if superior in MODULOS and superior != modulo and sub != "api":
                        violaciones.append(f"{ruta.relative_to(RAIZ)}:{linea} importa {superior}.{sub}")
        self.assertEqual(violaciones, [], "Un módulo importa algo que no es la interfaz pública de otro")

    def test_las_capas_internas_no_dependen_de_otros_modulos(self):
        violaciones = []
        for modulo in MODULOS:
            for ruta in _archivos(modulo):
                if _capa(modulo, ruta) in CAPAS_AISLADAS:
                    for superior, sub, linea in _importaciones(ruta, modulo):
                        if superior in MODULOS and superior != modulo:
                            violaciones.append(f"{ruta.relative_to(RAIZ)}:{linea} importa {superior}.{sub}")
        self.assertEqual(violaciones, [])

    def test_ningun_modulo_escribe_sql_contra_el_esquema_de_otro(self):
        violaciones = []
        for modulo, esquema_propio in MODULOS.items():
            ajenos = [e for e in MODULOS.values() if e != esquema_propio]
            patron = re.compile(r'\b(' + "|".join(ajenos) + r')"?\s*\.\s*"?[a-z_]+')
            for ruta in _archivos(modulo):
                for nodo in ast.walk(ast.parse(ruta.read_text(encoding="utf-8"))):
                    if isinstance(nodo, ast.Constant) and isinstance(nodo.value, str):
                        texto = nodo.value.lower()
                        if any(p in texto for p in ("select", "update", "insert", "delete", "db_table")) or '"."' in texto:
                            if patron.search(texto):
                                violaciones.append(f"{ruta.relative_to(RAIZ)}:{nodo.lineno} toca otro esquema")
        self.assertEqual(violaciones, [])

    def test_la_raiz_de_composicion_solo_usa_interfaces_publicas(self):
        violaciones = []
        for ruta in _archivos("config"):
            for superior, sub, linea in _importaciones(ruta, "config"):
                if superior in MODULOS and sub != "api":
                    violaciones.append(f"{ruta.relative_to(RAIZ)}:{linea} importa {superior}.{sub}")
        self.assertEqual(violaciones, [])


class CapasDentroDeCadaModulo(SimpleTestCase):
    def test_las_dependencias_entre_capas_apuntan_en_una_sola_direccion(self):
        violaciones = []
        for modulo in MODULOS:
            for ruta in _archivos(modulo):
                capa = _capa(modulo, ruta)
                self.assertIn(capa, CAPAS_PERMITIDAS, f"{ruta.relative_to(RAIZ)}: capa desconocida '{capa}'")
                for superior, sub, linea in _importaciones(ruta, modulo):
                    if superior == modulo and sub not in CAPAS_PERMITIDAS[capa]:
                        violaciones.append(f"{ruta.relative_to(RAIZ)}:{linea} ({capa}) importa {modulo}.{sub}")
        self.assertEqual(violaciones, [])

    def test_el_dominio_no_depende_de_django(self):
        violaciones = []
        for modulo in MODULOS:
            for superior, _, linea in _importaciones(RAIZ / modulo / "dominio.py", modulo):
                if superior == "django":
                    violaciones.append(f"{modulo}/dominio.py:{linea}")
        self.assertEqual(violaciones, [])

    def test_la_prueba_detecta_una_violacion(self):
        """Control de la propia función de aptitud: una importación prohibida sí se detecta."""
        falso = RAIZ / "tests" / "_violacion_tmp.py"
        falso.write_text("from atencion_clinica.models import RegistroClinico\n", encoding="utf-8")
        try:
            importaciones = list(_importaciones(falso, "agenda"))
        finally:
            falso.unlink()
        self.assertIn(("atencion_clinica", "models", 1), importaciones)
