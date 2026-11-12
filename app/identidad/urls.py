from django.urls import path

from . import vistas

urlpatterns = [
    path("sesiones", vistas.sesiones),
    path("yo", vistas.yo),
    path("medicos", vistas.medicos),
    path("pacientes", vistas.pacientes),
]
