from django.apps import AppConfig


class AtencionClinicaConfig(AppConfig):
    name = "atencion_clinica"
    verbose_name = "Atención clínica"

    def ready(self):
        # Atención clínica guarda su propia relación de tratamiento a partir del evento CitaReservada.
        from agenda import api as agenda

        from . import aplicacion

        agenda.suscribir(agenda.CitaReservada, aplicacion.registrar_relacion)
