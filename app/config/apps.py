from django.apps import AppConfig


class ConfigConfig(AppConfig):
    """Raíz de composición: no es un módulo de negocio; solo usa las interfaces públicas (api.py)."""

    name = "config"
    verbose_name = "Raíz de composición"
