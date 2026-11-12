from django.urls import include, path

from . import vistas

urlpatterns = [
    path("salud", vistas.salud),
    path("api/identidad/", include("identidad.urls")),
    path("api/agenda/", include("agenda.urls")),
    path("api/clinica/", include("atencion_clinica.urls")),
    path("api/notificaciones/", include("notificaciones.urls")),
]
