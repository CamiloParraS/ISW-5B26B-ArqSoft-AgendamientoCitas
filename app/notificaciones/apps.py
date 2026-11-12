from django.apps import AppConfig


class NotificacionesConfig(AppConfig):
    name = "notificaciones"
    verbose_name = "Notificaciones"

    def ready(self):
        # Notificaciones reacciona a los eventos de cita de Agenda.
        from agenda import api as agenda

        from . import aplicacion

        agenda.suscribir(agenda.CitaReservada, aplicacion.al_reservar)
        agenda.suscribir(agenda.CitaCancelada, aplicacion.al_cancelar)
        agenda.suscribir(agenda.RecordatorioDebido, aplicacion.al_recordatorio)
        agenda.suscribir(agenda.InasistenciaRegistrada, aplicacion.al_inasistencia)
