"""Dominio de Atención clínica: diagnósticos, recetas, relación de tratamiento y bitácora. Sin Django.

"Paciente" en Atención clínica = una historia clínica.
"""

LEER_HISTORIA = "LEER_HISTORIA"
REGISTRAR_ATENCION = "REGISTRAR_ATENCION"

PERMITIDO = "PERMITIDO"
DENEGADO = "DENEGADO"


class AccesoDenegado(Exception):
    """Se deniega sin revelar si hay registros."""


class DatosInvalidos(Exception):
    pass


def decidir_acceso(es_medico, tiene_relacion_de_tratamiento):
    """Autorizar por rol Y relación (ADR-003). Devuelve (permitido, motivo para la bitácora)."""
    if not es_medico:
        return False, "El rol no tiene acceso a datos clínicos"
    if not tiene_relacion_de_tratamiento:
        return False, "Sin relación de tratamiento con el paciente"
    return True, "Médico tratante"
