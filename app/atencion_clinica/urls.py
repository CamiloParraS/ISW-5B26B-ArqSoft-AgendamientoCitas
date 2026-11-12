from django.urls import path

from . import vistas

urlpatterns = [
    path("pacientes/<int:paciente_id>/historia", vistas.historia),
    path("pacientes/<int:paciente_id>/atenciones", vistas.atenciones),
]
