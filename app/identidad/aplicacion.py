"""Casos de uso de Identidad y acceso."""
import hashlib
import secrets
from datetime import timedelta

from django.conf import settings
from django.contrib.auth.hashers import check_password, make_password
from django.utils import timezone

from .dominio import ROLES, Contacto, CredencialesInvalidas, UsuarioActual
from .models import Sesion, Usuario


def _hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def _a_dominio(u):
    return UsuarioActual(id=u.id, usuario=u.usuario, nombre=u.nombre, rol=u.rol)


def crear_usuario(usuario, clave, nombre, rol, correo="", telefono=""):
    if rol not in ROLES:
        raise ValueError(f"Rol desconocido: {rol}")
    u, _ = Usuario.objects.get_or_create(
        usuario=usuario,
        defaults={"nombre": nombre, "rol": rol, "clave_hash": make_password(clave), "correo": correo, "telefono": telefono},
    )
    return _a_dominio(u)


def hay_usuarios():
    return Usuario.objects.exists()


def iniciar_sesion(usuario, clave):
    u = Usuario.objects.filter(usuario=usuario).first()
    if u is None or not check_password(clave, u.clave_hash):
        raise CredencialesInvalidas()
    token = secrets.token_urlsafe(32)
    Sesion.objects.create(token_hash=_hash(token), usuario=u, expira=timezone.now() + timedelta(hours=settings.SESION_HORAS))
    return token, _a_dominio(u)


def verificar_sesion(token):
    if not token:
        return None
    s = Sesion.objects.select_related("usuario").filter(token_hash=_hash(token), expira__gt=timezone.now()).first()
    return _a_dominio(s.usuario) if s else None


def cerrar_sesion(token):
    if token:
        Sesion.objects.filter(token_hash=_hash(token)).delete()


def obtener(usuario_id):
    u = Usuario.objects.filter(id=usuario_id).first()
    return _a_dominio(u) if u else None


def buscar(usuario):
    u = Usuario.objects.filter(usuario=usuario).first()
    return _a_dominio(u) if u else None


def nombres(ids):
    return dict(Usuario.objects.filter(id__in=set(ids)).values_list("id", "nombre"))


def contacto(usuario_id):
    u = Usuario.objects.get(id=usuario_id)
    return Contacto(correo=u.correo, telefono=u.telefono)


def listar(rol):
    return [_a_dominio(u) for u in Usuario.objects.filter(rol=rol).order_by("nombre")]
