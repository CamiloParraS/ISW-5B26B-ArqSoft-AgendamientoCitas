from django.urls import path

from . import vistas

urlpatterns = [
    path("cupos", vistas.cupos),
    path("citas", vistas.citas),
    path("citas/<int:cita_id>/cancelar", vistas.cancelar),
    path("citas/<int:cita_id>/reprogramar", vistas.reprogramar),
    path("citas/<int:cita_id>/asistencia", vistas.asistencia),
    path("horarios", vistas.horarios),
    path("reportes/mensual", vistas.reporte_mensual),
]
