from django.urls import path

from . import vistas

urlpatterns = [
    path("mias", vistas.mias),
]
