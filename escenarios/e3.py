"""E3 - Seguridad: consulta de una IPS aliada por documento."""

import comun as c
from comun import log

NOMBRE = "e3"
CAMPOS = {"fecha", "hora", "sede", "medico"}


def escenario():
    return c.Escenario(
        NOMBRE, "Seguridad: consulta de una IPS aliada",
        "Administrativo de una IPS aliada",
        "Consulta las citas de un paciente por documento",
        "Modulo Acceso interinstitucional",
        "Operacion normal en horario de ventanilla",
        "Muestra solo fecha, hora, sede y medico de citas con consentimiento vigente y registra el acceso",
        "100 % de accesos en bitacora inmutable; 0 datos clinicos expuestos en pruebas de autorizacion")


def consultar(usuario, documento, correlacion):
    return c.peticion("GET", f"/api/acceso/consultas/citas?documento={documento}", token=c.token(usuario),
                      correlacion=correlacion, timeout=15)


def cuerpo(esc, limp):
    tok_ana = c.token("ana")
    # Ana necesita al menos una cita vigente para que la consulta tenga contenido
    cupos = c.cupos_libres(tok_ana, c.ESP_CARDIOLOGIA, c.MEDICO_ORTIZ, desde_dias=2, minimo=1)
    if not cupos:
        raise c.ErrorEscenario("No hay cupos libres del Dr. Ortiz")
    cupo = cupos[0]
    cita = c.reservar_simple("ana", cupo)
    limp.agregar("cancelar la cita de prueba de Ana", lambda: c.cancelar(c.token("ana"), cita["id"], timeout=15))
    ini = c.a_bogota(cupo["inicio"])
    fecha, hora = ini.strftime("%Y-%m-%d"), ini.strftime("%H:%M")
    log(f"cita de Ana: {cita['id']} {fecha} {hora}")

    def n_bitacora():
        r = c.peticion("GET", "/api/acceso/bitacora/mia", token=tok_ana, timeout=15)
        if not r.ok:
            raise c.ErrorEscenario(f"bitacora/mia de Ana: {r.resumen()}")
        return c.lista_de(r.datos), r.texto
    antes, _ = n_bitacora()

    corr = {k: f"e3-{k}-{c.nuevo_id()[:8]}" for k in ("norte", "sur", "desconocido")}

    # 1) IPS-NORTE (con consentimiento)
    r_norte = consultar("admin.norte", c.ANA_DOCUMENTO, corr["norte"])
    log(f"admin.norte -> {r_norte.resumen()}")
    filas = r_norte.datos if isinstance(r_norte.datos, list) else []
    solo_cuatro = r_norte.status == 200 and bool(filas) and all(isinstance(f, dict) and set(f.keys()) == CAMPOS for f in filas)
    contiene_cita = any(isinstance(f, dict) and f.get("fecha") == fecha and f.get("hora") == hora for f in filas)

    # 2) IPS-SUR (sin consentimiento)
    r_sur = consultar("admin.sur", c.ANA_DOCUMENTO, corr["sur"])
    log(f"admin.sur -> {r_sur.resumen()}")
    sur_sin_datos = not any(k in r_sur.texto for k in ('"medico"', '"sede"', '"fecha"', '"hora"'))

    # 3) un paciente no puede usar la consulta interinstitucional
    r_pac = c.peticion("GET", f"/api/acceso/consultas/citas?documento={c.ANA_DOCUMENTO}", token=c.token("luis"), timeout=15)
    log(f"luis (paciente) -> {r_pac.resumen()}")

    # 4) documento desconocido (tambien se audita)
    r_desc = consultar("admin.norte", "9999999999", corr["desconocido"])
    log(f"admin.norte documento desconocido -> {r_desc.resumen()}")

    # 5) bitacora de Ana
    despues, texto = n_bitacora()
    delta = len(despues) - len(antes)
    ve_norte = "IPS-NORTE" in texto or "Hospital Aliado del Norte" in texto
    ve_sur = "IPS-SUR" in texto or "Clínica Aliada del Sur" in texto or "Cl\\u00ednica Aliada del Sur" in texto
    log(f"bitacora/mia de Ana: {len(antes)} -> {len(despues)} registros")

    # 6) bitacora en BD: 100 % de las consultas administrativas auditadas
    esperados = list(corr.values())
    lista = ",".join(f"'{x}'" for x in esperados)
    filas_bd = c.psql_filas(f"SELECT correlacion_id, resultado, institucion_codigo FROM acceso.bitacora_acceso "
                            f"WHERE correlacion_id IN ({lista}) ORDER BY id")
    auditados = {f[0] for f in filas_bd}
    log(f"bitacora en BD: {filas_bd}")

    # 7) inmutabilidad
    antes_n = int(c.psql_filas("SELECT count(*) FROM acceso.bitacora_acceso")[0][0])
    sentencias = {
        "UPDATE": "UPDATE acceso.bitacora_acceso SET motivo = 'manipulado' WHERE id = (SELECT max(id) FROM acceso.bitacora_acceso)",
        "DELETE": "DELETE FROM acceso.bitacora_acceso WHERE id = (SELECT max(id) FROM acceso.bitacora_acceso)",
        "TRUNCATE": "TRUNCATE acceso.bitacora_acceso",
    }
    rechazadas = {}
    for nombre, sql in sentencias.items():
        rc, out, err = c.psql(sql, reintentar=False)
        rechazadas[nombre] = rc != 0 and "solo admite inserciones" in err
        log(f"{nombre} sobre bitacora -> rc={rc} {err.strip()[:120]!r}")
    despues_n = int(c.psql_filas("SELECT count(*) FROM acceso.bitacora_acceso")[0][0])

    esc.mide("admin.norte -> 200 con SOLO fecha, hora, sede, medico", f"HTTP {r_norte.status}, campos {sorted(set().union(*[set(f) for f in filas if isinstance(f, dict)])) if filas else '-'}",
             solo_cuatro and contiene_cita)
    esc.mide("admin.sur (sin consentimiento) -> 403 SIN_CONSENTIMIENTO sin datos",
             f"HTTP {r_sur.status} {r_sur.codigo()}", r_sur.status == 403 and r_sur.codigo() == "SIN_CONSENTIMIENTO" and sur_sin_datos)
    esc.mide("Paciente (luis) en la consulta interinstitucional -> 403", f"HTTP {r_pac.status}", r_pac.status == 403)
    esc.mide("Documento desconocido -> 404", f"HTTP {r_desc.status}", r_desc.status == 404)
    esc.mide("Bitacora/mia de Ana muestra ambas consultas (norte y sur)", f"+{delta} registros; norte={ve_norte} sur={ve_sur}",
             delta >= 2 and ve_norte and ve_sur)
    esc.mide("Accesos administrativos registrados en bitacora (== 100 %)",
             f"{len(auditados)} de {len(esperados)}", auditados == set(esperados))
    esc.mide("Bitacora inmutable: UPDATE, DELETE y TRUNCATE rechazados",
             ", ".join(f"{k}={'rechazado' if v else 'PERMITIDO'}" for k, v in rechazadas.items()) + f"; filas {antes_n}->{despues_n}",
             all(rechazadas.values()) and antes_n == despues_n)


def ejecutar():
    return c.correr(escenario(), cuerpo)
