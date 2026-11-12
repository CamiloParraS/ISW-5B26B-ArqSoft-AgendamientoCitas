"""Dominio de Notificaciones: plantillas, canales y envíos. Sin Django.

"Paciente" en Notificaciones = un destinatario. Los mensajes nunca llevan datos clínicos.
"""

CONFIRMACION = "CONFIRMACION"
CANCELACION = "CANCELACION"
RECORDATORIO = "RECORDATORIO"
INASISTENCIA = "INASISTENCIA"
TIPOS = (CONFIRMACION, CANCELACION, RECORDATORIO, INASISTENCIA)

PENDIENTE = "PENDIENTE"
ENVIADO = "ENVIADO"
ESTADOS = (PENDIENTE, ENVIADO)

PLANTILLAS = {
    CONFIRMACION: "Su cita con {medico} el {fecha} a las {hora} quedó confirmada.",
    CANCELACION: "Su cita con {medico} del {fecha} a las {hora} fue cancelada.",
    RECORDATORIO: "Recordatorio: mañana {fecha} a las {hora} tiene cita con {medico}.",
    INASISTENCIA: "Registramos su inasistencia a la cita del {fecha} a las {hora} con {medico}.",
}


def redactar(tipo, medico, inicio_local):
    return PLANTILLAS[tipo].format(medico=medico, fecha=inicio_local.strftime("%d/%m/%Y"), hora=inicio_local.strftime("%H:%M"))
