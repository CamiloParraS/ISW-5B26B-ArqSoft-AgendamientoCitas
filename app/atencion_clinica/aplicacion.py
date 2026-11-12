"""Casos de uso de Atención clínica (ADR-003)."""
from django.db import transaction
from django.db.models import Max
from django.utils import timezone

from identidad import api as identidad

from . import cifrado
from . import dominio as d
from . import models as m


def registrar_relacion(evento):
    """Manejador de CitaReservada: guarda la relación de tratamiento en el esquema clínico."""
    m.RelacionTratamiento.objects.get_or_create(
        medico_id=evento.medico_id, paciente_id=evento.paciente_id, defaults={"cita_origen_id": evento.cita_id}
    )


def _autorizar(usuario, accion, paciente_id):
    """Rol + relación de tratamiento en cada operación clínica, y cada intento queda en la bitácora."""
    es_medico = usuario.rol == identidad.MEDICO
    tiene_relacion = es_medico and m.RelacionTratamiento.objects.filter(medico_id=usuario.id, paciente_id=paciente_id).exists()
    permitido, motivo = d.decidir_acceso(es_medico, tiene_relacion)
    m.BitacoraAcceso.objects.create(
        usuario_id=usuario.id,
        rol=usuario.rol,
        accion=accion,
        paciente_id=paciente_id,
        resultado=d.PERMITIDO if permitido else d.DENEGADO,
        motivo=motivo,
    )
    if not permitido:
        raise d.AccesoDenegado()


def _registro_dict(r, nombres):
    return {
        "id": r.id,
        "cita_id": r.cita_id,
        "version": r.version,
        "medico_id": r.medico_id,
        "medico": nombres.get(r.medico_id),
        "diagnostico": cifrado.descifrar(r.diagnostico_cifrado),
        "receta": cifrado.descifrar(r.receta_cifrada),
        "registrado": timezone.localtime(r.creado).isoformat(),
    }


def leer_historia(usuario, paciente_id):
    _autorizar(usuario, d.LEER_HISTORIA, paciente_id)
    # Última versión de cada atención.
    ultimas = list(m.RegistroClinico.objects.filter(paciente_id=paciente_id).order_by("cita_id", "-version").distinct("cita_id"))
    ultimas.sort(key=lambda r: r.creado, reverse=True)
    nombres = identidad.nombres(r.medico_id for r in ultimas)
    return [_registro_dict(r, nombres) for r in ultimas]


def registrar_atencion(usuario, paciente_id, cita_id, diagnostico, receta):
    _autorizar(usuario, d.REGISTRAR_ATENCION, paciente_id)
    if not (diagnostico or "").strip():
        raise d.DatosInvalidos("El diagnóstico es obligatorio")
    with transaction.atomic():
        ultima = m.RegistroClinico.objects.filter(cita_id=cita_id).aggregate(v=Max("version"))["v"] or 0
        r = m.RegistroClinico.objects.create(
            paciente_id=paciente_id,
            medico_id=usuario.id,
            cita_id=cita_id,
            version=ultima + 1,
            diagnostico_cifrado=cifrado.cifrar(diagnostico.strip()),
            receta_cifrada=cifrado.cifrar((receta or "").strip()),
        )
    return _registro_dict(r, identidad.nombres([usuario.id]))


def bitacora(limite=50):
    return list(m.BitacoraAcceso.objects.order_by("-momento", "-id")[:limite])
