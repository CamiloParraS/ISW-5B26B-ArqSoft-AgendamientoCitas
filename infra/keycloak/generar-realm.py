#!/usr/bin/env python3
"""Genera infra/keycloak/medicita-realm.json (realm `medicita`, SPEC §4).

Uso: python infra/keycloak/generar-realm.py
Solo biblioteca estándar. El JSON generado también se versiona.
"""
import json
import pathlib

SALIDA = pathlib.Path(__file__).with_name("medicita-realm.json")
CLAVE = "medicita"

ROLES = ["paciente", "medico", "administrativo_ips", "coordinador"]
ATRIBUTOS = ["documento", "telefono", "institucion"]


def usuario(uid, username, nombre, apellido, rol, atributos=None, email=None):
    u = {
        "id": uid,
        "username": username,
        "enabled": True,
        "emailVerified": True,
        "firstName": nombre,
        "lastName": apellido,
        "credentials": [{"type": "password", "value": CLAVE, "temporary": False}],
        "realmRoles": [rol],
        "requiredActions": [],
    }
    if email:
        u["email"] = email
    if atributos:
        u["attributes"] = {k: [v] for k, v in atributos.items()}
    return u


def usuarios():
    lista = [
        usuario("a0000000-0000-4000-8000-000000000001", "ana", "Ana", "Gómez", "paciente",
                {"documento": "1085000001", "telefono": "+573001110001"}, "ana@medicita.test"),
        usuario("a0000000-0000-4000-8000-000000000002", "luis", "Luis", "Pérez", "paciente",
                {"documento": "1085000002", "telefono": "+573001110002"}, "luis@medicita.test"),
    ]
    for n in range(1, 51):
        nn = f"{n:02d}"
        lista.append(usuario(
            f"a0000000-0000-4000-8000-0000000001{nn}", f"carga{nn}", "Paciente", f"Carga {nn}", "paciente",
            {"documento": f"10859000{nn}", "telefono": f"+5730022200{nn}"}, f"carga{nn}@medicita.test"))
    lista += [
        usuario("b0000000-0000-4000-8000-000000000001", "dra.rosero", "Carolina", "Rosero", "medico"),
        usuario("b0000000-0000-4000-8000-000000000002", "dr.burbano", "Andrés", "Burbano", "medico"),
        usuario("b0000000-0000-4000-8000-000000000003", "dr.ortiz", "Jorge", "Ortiz", "medico"),
        usuario("d0000000-0000-4000-8000-000000000001", "admin.norte", "Marta", "Delgado", "administrativo_ips",
                {"institucion": "IPS-NORTE"}),
        usuario("d0000000-0000-4000-8000-000000000002", "admin.sur", "Pedro", "Insuasty", "administrativo_ips",
                {"institucion": "IPS-SUR"}),
        usuario("e0000000-0000-4000-8000-000000000001", "coordinacion", "Coordinación", "de agendas", "coordinador"),
    ]
    return lista


def perfil_usuario():
    """Perfil declarativo (Keycloak 26): declara documento/telefono/institucion para conservarlos."""
    def attr(nombre, display, requerido=None):
        a = {
            "name": nombre,
            "displayName": display,
            "validations": {},
            "permissions": {"view": ["admin", "user"], "edit": ["admin"]},
        }
        if requerido:
            a["required"] = {"roles": requerido}
        return a

    return {
        "attributes": [
            {"name": "username", "displayName": "${username}",
             "validations": {"length": {"min": 3, "max": 255}, "username-prohibited-characters": {}, "up-username-not-idn-homograph": {}},
             "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}},
            {"name": "email", "displayName": "${email}",
             "validations": {"email": {}, "length": {"max": 255}},
             "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}},
            {"name": "firstName", "displayName": "${firstName}",
             "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
             "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}},
            {"name": "lastName", "displayName": "${lastName}",
             "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
             "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}},
            attr("documento", "Documento"),
            attr("telefono", "Teléfono"),
            attr("institucion", "Institución"),
        ],
        "unmanagedAttributePolicy": "ENABLED",
    }


def mapper(attr):
    return {
        "name": attr,
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-attribute-mapper",
        "consentRequired": False,
        "config": {
            "user.attribute": attr,
            "claim.name": attr,
            "jsonType.label": "String",
            "id.token.claim": "true",
            "access.token.claim": "true",
            "userinfo.token.claim": "true",
            "introspection.token.claim": "true",
            "multivalued": "false",
            "aggregate.attrs": "false",
        },
    }


ALCANCES_POR_DEFECTO = ["web-origins", "acr", "profile", "roles", "email", "basic", "medicita"]
ALCANCES_OPCIONALES = ["offline_access"]


def _m(nombre, tipo, **config):
    return {"name": nombre, "protocol": "openid-connect", "protocolMapper": tipo,
            "consentRequired": False, "config": {k: str(v).lower() if isinstance(v, bool) else v for k, v in config.items()}}


def _alcance(nombre, mappers=(), descripcion=""):
    return {"name": nombre, "description": descripcion, "protocol": "openid-connect",
            "attributes": {"include.in.token.scope": "true" if nombre in ("profile", "email", "offline_access") else "false",
                           "display.on.consent.screen": "false"},
            "protocolMappers": list(mappers)}


def alcances_estandar():
    """Al declarar `clientScopes` en el import Keycloak NO crea los alcances por defecto: se declaran aquí."""
    tok = dict(**{"id.token.claim": True, "access.token.claim": True, "userinfo.token.claim": True})
    return [
        _alcance("basic", [
            _m("sub", "oidc-sub-mapper", **{"access.token.claim": True, "introspection.token.claim": True}),
            _m("auth_time", "oidc-usersessionmodel-note-mapper", **{
                "user.session.note": "AUTH_TIME", "claim.name": "auth_time", "jsonType.label": "long",
                "id.token.claim": True, "access.token.claim": True, "introspection.token.claim": True}),
        ]),
        _alcance("roles", [
            _m("realm roles", "oidc-usermodel-realm-role-mapper", **{
                "claim.name": "realm_access.roles", "jsonType.label": "String", "multivalued": True,
                "access.token.claim": True, "id.token.claim": False, "userinfo.token.claim": False,
                "introspection.token.claim": True}),
            _m("client roles", "oidc-usermodel-client-role-mapper", **{
                "claim.name": "resource_access.${client_id}.roles", "jsonType.label": "String", "multivalued": True,
                "access.token.claim": True, "id.token.claim": False, "userinfo.token.claim": False,
                "introspection.token.claim": True}),
            _m("audience resolve", "oidc-audience-resolve-mapper", **{
                "access.token.claim": True, "introspection.token.claim": True}),
        ]),
        _alcance("profile", [
            _m("username", "oidc-usermodel-attribute-mapper", **{
                "user.attribute": "username", "claim.name": "preferred_username", "jsonType.label": "String", **tok}),
            _m("full name", "oidc-full-name-mapper", **tok),
            _m("given name", "oidc-usermodel-attribute-mapper", **{
                "user.attribute": "firstName", "claim.name": "given_name", "jsonType.label": "String", **tok}),
            _m("family name", "oidc-usermodel-attribute-mapper", **{
                "user.attribute": "lastName", "claim.name": "family_name", "jsonType.label": "String", **tok}),
        ]),
        _alcance("email", [
            _m("email", "oidc-usermodel-attribute-mapper", **{
                "user.attribute": "email", "claim.name": "email", "jsonType.label": "String", **tok}),
            _m("email verified", "oidc-usermodel-property-mapper", **{
                "user.attribute": "emailVerified", "claim.name": "email_verified", "jsonType.label": "boolean", **tok}),
        ]),
        _alcance("web-origins", [
            _m("allowed web origins", "oidc-allowed-origins-mapper", **{
                "access.token.claim": True, "introspection.token.claim": True})]),
        _alcance("acr", [
            _m("acr loa level", "oidc-acr-mapper", **{
                "id.token.claim": True, "access.token.claim": True, "introspection.token.claim": True})]),
        _alcance("offline_access"),
    ]


def cliente(client_id, nombre, redirecciones, **extra):
    c = {
        "clientId": client_id,
        "name": nombre,
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": True,
        "standardFlowEnabled": True,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False,
        "fullScopeAllowed": True,
        "redirectUris": redirecciones,
        "webOrigins": ["+"],
        "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"},
        "defaultClientScopes": ALCANCES_POR_DEFECTO,
        "optionalClientScopes": ALCANCES_OPCIONALES,
    }
    c.update(extra)
    return c


def realm():
    return {
        "realm": "medicita",
        "displayName": "MediCita",
        "enabled": True,
        "sslRequired": "external",
        "registrationAllowed": False,
        "loginWithEmailAllowed": False,
        "duplicateEmailsAllowed": True,
        "resetPasswordAllowed": False,
        "editUsernameAllowed": False,
        "bruteForceProtected": False,
        "ssoSessionIdleTimeout": 900,
        "accessTokenLifespan": 300,
        "internationalizationEnabled": True,
        "supportedLocales": ["es", "en"],
        "defaultLocale": "es",
        "roles": {"realm": [{"name": r, "description": f"Rol {r} de MediCita"} for r in ROLES]},
        "clientScopes": alcances_estandar() + [{
            "name": "medicita",
            "description": "Atributos de MediCita (documento, telefono, institucion)",
            "protocol": "openid-connect",
            "attributes": {"include.in.token.scope": "false", "display.on.consent.screen": "false"},
            "protocolMappers": [mapper(a) for a in ATRIBUTOS],
        }],
        "clients": [
            cliente("app-paciente", "App web del paciente",
                    ["https://localhost/*", "https://localhost:8443/*"]),
            cliente("portal", "Portal clinico-administrativo",
                    ["https://localhost/portal/*", "https://localhost:8443/portal/*"]),
            cliente("escenarios", "Escenarios de calidad (scripts)",
                    ["https://localhost/*", "https://localhost:8443/*"],
                    directAccessGrantsEnabled=True),
        ],
        "users": usuarios(),
        "components": {
            "org.keycloak.userprofile.UserProfileProvider": [{
                "providerId": "declarative-user-profile",
                "subComponents": {},
                "config": {"kc.user.profile.config": [json.dumps(perfil_usuario(), ensure_ascii=False)]},
            }]
        },
    }


if __name__ == "__main__":
    SALIDA.write_text(json.dumps(realm(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"escrito {SALIDA} ({len(usuarios())} usuarios)")
