# MediCita — esqueleto ejecutable de la arquitectura

Agendador de citas médicas siempre disponible, conectado a la historia clínica y con avisos por WhatsApp. Proyecto integrador de Arquitectura de software (UCC Pasto, 2026).

Este repositorio es un **walking skeleton**: materializa el diagrama C4 de nivel 2 de la sustentación, caja por caja y flecha por flecha, y trae scripts que **demuestran con números** los escenarios de calidad (E1, E2, E3) y las tácticas principales. Los sistemas externos (WhatsApp, pasarela SMS, HCE) están simulados.

**La decisión en un párrafo.** El núcleo (Agenda, Red de prestadores y Acceso interinstitucional) es un **monolito modular** en Spring Boot con Spring Modulith, en 2+ instancias, porque reservar un cupo exige una transacción local sin doble asignación. Notificaciones e Integración clínica son **servicios satélite dirigidos por eventos**: la API guarda la cita y el evento en una **outbox** en la misma transacción y un relé lo publica en **RabbitMQ** (3 nodos, colas quorum, DLQ), de modo que una HCE o un WhatsApp caídos nunca frenan una reserva. Sacrificamos escalar por separado las partes del núcleo, simplicidad operativa y consistencia inmediata en avisos e HCE. Un test de Spring Modulith rompe el build si un módulo toca tablas o clases internas de otro. Detalle en [`docs/arquitectura.md`](docs/arquitectura.md) y en los ADR:

- [ADR-001 · Monolito modular para el núcleo y servicios satélite por eventos](docs/adr/ADR-001-estilo-arquitectonico.md)
- [ADR-002 · Mensajería asíncrona con outbox transaccional sobre RabbitMQ](docs/adr/ADR-002-mensajeria-outbox-rabbitmq.md)
- [ADR-003 · Canales de notificación como adaptadores enchufables](docs/adr/ADR-003-canales-notificacion.md)

## Requisitos

- Docker Desktop (o Docker Engine + Compose v2) con **al menos 8 GB de RAM** asignados.
- Puertos libres en el equipo: 443 (o 8443, ver [solución de problemas](#solución-de-problemas)), 80, 8025, 8090 y 15672.
- Para las pruebas de arquitectura fuera de Docker (opcional): JDK 21+ y Maven 3.9.

## Arranque: un solo comando

```bash
docker compose up -d --build
```

La primera vez construye las imágenes (Maven y npm descargan dependencias) y arranca ~20 contenedores: **puede tardar varios minutos**. Sabrá que está listo cuando todos los servicios figuren como `healthy`:

```bash
docker compose ps
```

Todos los contenedores tienen healthcheck y se arrancan en orden (`depends_on: condition: service_healthy`), así que mientras alguno aparezca como `starting` hay que esperar. Los datos semilla (sedes, médicos, horarios de los próximos 21 días calendario (cupos sólo de lunes a viernes), consentimiento de Ana) se cargan solos en el primer arranque.

## URLs

| Qué | URL |
|---|---|
| App del paciente (PWA) | https://localhost |
| Portal clínico-administrativo (médico, IPS aliada, coordinación) | https://localhost/portal/ |
| Keycloak (consola de administración, `admin` / `admin`) | https://localhost/auth/admin |
| Mailpit (correos de respaldo) | http://localhost:8025 |
| Panel de simuladores externos (WhatsApp, SMS, HCE) | http://localhost:8090 |
| RabbitMQ (gestión, `medicita` / `medicita`) | http://localhost:15672 |

**HTTPS en localhost (una sola vez por equipo).** Al primer arranque el gateway crea una autoridad local en `infra/gateway/certs/` (ignorada por git y conservada entre reinicios) y firma con ella el certificado de `localhost`. Para que Chrome y Edge la acepten sin advertencia, instálela en su usuario de Windows:

```powershell
powershell -ExecutionPolicy Bypass -File infra\gateway\confiar-certificado.ps1
```

Windows pedirá confirmación; acéptela y reinicie el navegador. La autoridad está restringida con *nameConstraints*: sólo es válida para `localhost`, `127.0.0.1` y `::1`, así que no puede usarse para suplantar ningún otro sitio. Para quitarla: el mismo comando con `-Quitar`. (Firefox usa su propio almacén: importe `infra/gateway/certs/medicita-ca.crt` en Ajustes → Certificados, o active `security.enterprise_roots.enabled`.)

## Usuarios demo

Contraseña de **todos**: `medicita`.

| Usuario | Rol | Quién es | Para qué sirve en la demo |
|---|---|---|---|
| `ana` | paciente | Ana Gómez (prefiere WhatsApp; autorizó a IPS-NORTE) | Agendar y ver el aviso por WhatsApp |
| `luis` | paciente | Luis Pérez (prefiere SMS) | Segundo paciente; no tiene autorizaciones |
| `carga01` … `carga50` | paciente | Pacientes de carga (aviso por correo) | Los usan los escenarios E1 y E2 |
| `dra.rosero` | médico | Carolina Rosero · Medicina general · Sede Centro | Ver agenda y marcar atendida |
| `dr.burbano` | médico | Andrés Burbano · Pediatría · Sede Norte | |
| `dr.ortiz` | médico | Jorge Ortiz · Cardiología · Sede Centro | |
| `admin.norte` | administrativo_ips | Marta Delgado · IPS-NORTE (Hospital Aliado del Norte) | Consulta a Ana: **permitido** (hay consentimiento) |
| `admin.sur` | administrativo_ips | Pedro Insuasty · IPS-SUR (Clínica Aliada del Sur) | Consulta a Ana: **denegado** (sin consentimiento) |
| `coordinacion` | coordinador | Coordinación de agendas | Cambiar horas mínimas de cancelación, reportes, estado de la outbox |

Documento de Ana para la consulta de las IPS: `1085000001`.

## Recorrido de demostración en vivo

Ordenado para la sustentación. Tenga abiertas en pestañas: la PWA, el portal, el panel de simuladores (http://localhost:8090) y Mailpit.

1. **Agendar como paciente.** Abra https://localhost e inicie sesión como `ana`. Siga los tres pasos: 1 Especialidad → 2 Cupo (o «Primer cupo disponible») → 3 Confirmar. Observe la cuenta regresiva de 5 minutos de la retención y toque *Confirmar*; la cita aparece en «Mis citas».
2. **El aviso por WhatsApp.** En el panel de simuladores (http://localhost:8090, se refresca cada 3 s) aparece el mensaje de plantilla `cita_confirmada` dirigido al teléfono de Ana. Eso es la outbox → RabbitMQ → Notificaciones → WhatsApp. (Si el paciente fuese `luis`, el aviso sale por SMS; con `carga01`, por correo en Mailpit.)
3. **El médico atiende.** En una ventana privada abra https://localhost/portal/ e inicie sesión como el médico de la especialidad elegida (p. ej. `dra.rosero` para Medicina general). En «Agenda del día» (elija la fecha de la cita) marque la cita como **atendida**.
4. **La HCE recibe el recurso FHIR.** En el panel de simuladores, sección HCE, aparecen el `Appointment` actualizado a `fulfilled` y el `Encounter` creado por la capa anticorrupción de Integración clínica (≤ 15 min exigidos; normalmente segundos).
5. **Acceso interinstitucional (E3).** Entre al portal como `admin.norte` y consulte el documento `1085000001`: obtiene una tabla con **sólo** fecha, hora, sede y médico. Cierre sesión, entre como `admin.sur` y consulte el mismo documento: mensaje «El paciente no ha autorizado a su institución» (403). En la PWA, como `ana`, abra «Quién consultó mis datos»: están ambos accesos (permitido y denegado), registrados en una bitácora que sólo admite inserciones.
6. **Cambiar una regla sin redesplegar.** Entre al portal como `coordinacion`, abra «Políticas de cancelación» y cambie las horas mínimas de una sede (p. ej. de 24 a 48). El cambio rige de inmediato: cancele una cita con menos de 48 h de antelación en esa sede y verá la advertencia de penalidad, sin reiniciar nada.
7. **Tolerancia a fallos en vivo (opcional).** En otra terminal: `docker compose run --rm escenarios e1` (cae una instancia y la BD primaria mientras se reserva) o `docker compose stop agendamiento-1` y siga agendando.

## Escenarios de calidad

Cada escenario imprime las seis partes (Fuente / Estímulo / Artefacto / Entorno / Respuesta / Medida), mide contra la medida del deck y termina con un veredicto **PASA / NO PASA** (código de salida 0 sólo si todas las medidas pasan). Siempre restauran lo que detienen (contenedores, plantillas, HCE), incluso si fallan.

```bash
docker compose run --rm escenarios <nombre>
```

| Nombre | Qué demuestra | Medida |
|---|---|---|
| `e1` | Disponibilidad: con 5 pacientes reservando sin parar se detiene `agendamiento-1` y luego la BD primaria de Patroni; los reintentos usan la misma clave de idempotencia | Reserva restablecida ≤ 60 s; 0 citas confirmadas perdidas |
| `e2` | Escalabilidad: 50 pacientes retienen el mismo cupo a la vez; luego rondas de confirmaciones concurrentes | Exactamente 1 ganador (49 × 409 con alternativas); 0 dobles asignaciones en BD; confirmación p95 ≤ 800 ms |
| `e3` | Seguridad: `admin.norte`, `admin.sur`, un paciente y un documento desconocido consultan a Ana | Sólo 4 campos; 100 % de accesos en bitácora; `UPDATE`/`DELETE`/`TRUNCATE` de la bitácora rechazados |
| `hce-caida` | La HCE cae: se agenda y se atiende igual, el circuit breaker se abre; al volver llegan `Appointment` y `Encounter` | ≤ 15 min (se espera < 2 min) |
| `respaldo-canal` | Se rechaza la plantilla `cita_confirmada` de WhatsApp; el aviso sale por SMS | Respaldo ≤ 2 min |
| `broker-caido` | Se detienen los 3 nodos de RabbitMQ: la reserva sigue, la outbox acumula, al volver el broker se drena y llega el aviso. La alarma R5 (`alarma` en `/api/agenda/operacion/outbox`, evento pendiente > 5 min) se verifica manteniendo el broker caído más de 5 min | 0 eventos perdidos |
| `busqueda` | Rendimiento: 50 pacientes (`carga01..50`) buscan cupos durante ~30 s (`/cupos/proximos` y `/cupos` por fecha); reporta p95 y la proporción de `X-Cache: HIT`. Escala reducida: no son los 3.000 usuarios y el riesgo R2 pide la prueba de carga completa | Buscar cupos p95 ≤ 500 ms |
| `traza` | Un `X-Correlation-Id` conocido se rastrea en gateway, API, outbox, Notificaciones y WhatsApp | Presente en las 5 ubicaciones (informa además los segundos hasta el registro de WhatsApp) |
| `todos` | Ejecuta todos en secuencia y resume | — |

Sin argumentos, `docker compose run --rm escenarios` imprime la ayuda. Los escenarios necesitan el stack levantado y acceso a `/var/run/docker.sock` (lo monta el perfil `escenarios` del compose); cada uno usa médicos y días distintos para no interferir y cancela las citas que crea. `e1`, `e2` y `broker-caido` pueden tardar varios minutos.

## Pruebas de arquitectura

El estilo declarado **se ve en el código**: las pruebas de arquitectura del monolito corren en la fase `package`, así que **rompen el build** (incluida la construcción de la imagen Docker) si un módulo usa tablas o clases internas de otro.

```bash
# dentro de Docker: la imagen ejecuta mvn -B package (pruebas unitarias + de arquitectura)
docker compose build agendamiento-1

# o localmente
mvn -f servicios/agendamiento package

# pruebas de integración (Testcontainers: PostgreSQL, Redis, RabbitMQ; requiere Docker)
mvn -f servicios/agendamiento verify
```

- `ModularidadTest`: `ApplicationModules.of(MediCitaAplicacion.class).verify()` y genera la documentación de módulos en `servicios/agendamiento/target/spring-modulith-docs`.
- `PropiedadDeTablasTest`: cada entidad usa el esquema de su módulo; nada fuera del módulo referencia sus repositorios o entidades; ningún SQL nativo cruza esquemas.
- `*IT` (con `verify`): `CarreraPorCupoIT`, `IdempotenciaIT`, `OutboxIT`, `BitacoraSoloInsercionIT`.

Para ver el test fallar: haga que un módulo importe una clase `interno` de otro y vuelva a ejecutar `mvn -f servicios/agendamiento package`.

## Mapa: Contenedor C4 → servicio compose → carpeta

| Contenedor C4 | Servicio(s) compose | Carpeta |
|---|---|---|
| App web del paciente [React PWA] | `app-paciente` | `apps/paciente/` |
| Portal clínico-administrativo [React SPA] | `app-portal` | `apps/portal/` |
| Identidad [Keycloak] | `keycloak` | `infra/keycloak/` (realm `medicita`) |
| API Gateway [NGINX] | `gateway` | `infra/gateway/` |
| API de Agendamiento [Spring Modulith, 2+ instancias] | `agendamiento-1`, `agendamiento-2` | `servicios/agendamiento/` |
| Caché de cupos [Redis] | `cache-cupos` | imagen `redis` (sin carpeta propia) |
| BD de Agendamiento [PostgreSQL 16, primaria + réplica] | `bd-agendamiento-1`, `bd-agendamiento-2`, `etcd` (DCS de Patroni) | `infra/bd-agendamiento/` |
| Broker de mensajes [RabbitMQ, 3 nodos] | `rabbitmq-1`, `rabbitmq-2`, `rabbitmq-3` | `infra/rabbitmq/` |
| Servicio de Notificaciones [Spring Boot] | `notificaciones` | `servicios/notificaciones/` |
| BD Notificaciones [PostgreSQL] | `bd-notificaciones` | imagen `postgres` (esquema por Flyway en `servicios/notificaciones/`) |
| Servicio de Integración Clínica [Spring Boot · HAPI FHIR] | `integracion-clinica` | `servicios/integracion-clinica/` |
| WhatsApp Business, SMS y correo, HCE [sistemas externos] | `simuladores` (WhatsApp Cloud API, pasarela SMS, HCE FHIR R4), `correo-smtp` (Mailpit) | `externos/simuladores/` |
| (herramienta) escenarios | `escenarios` (perfil `escenarios`) | `escenarios/` |
| Contratos de eventos (`CitaAgendada`, `CitaCancelada`, `CitaAtendida` v1) | — | `contratos/eventos/` |

## Mapa: Táctica → dónde está en el código → escenario

| Táctica | Dónde está | Escenario |
|---|---|---|
| Redundancia activa con failover | Dos instancias (NGINX de código abierto hace comprobaciones **pasivas**: `max_fails`/`fail_timeout` y `proxy_next_upstream`; los healthchecks de Docker sondean activamente cada instancia; las comprobaciones activas de NGINX son de NGINX Plus). Dos instancias `agendamiento-1/2` y upstream con `max_fails`/`proxy_next_upstream` en `infra/gateway/nginx.conf`; réplica y promoción automática con Patroni en `infra/bd-agendamiento/` | `e1` |
| Reintento idempotente | Cabecera `Idempotency-Key`, tabla `agenda.clave_idempotencia` y confirmación transaccional en el módulo `agenda` (`co.edu.ucc.medicita.agenda`); la PWA genera la clave en `apps/paciente/` | `e1` |
| Circuit breaker y degradación elegante | Instancia Resilience4j `hce` y cola `integracion-clinica.citas.reintento` en `co.edu.ucc.medicita.integracionclinica` (`servicios/integracion-clinica/`) | `hce-caida` |
| Unicidad en BD y retención temporal | `UNIQUE(medico_id, inicio)` y `UPDATE` condicional en `agenda.cupo` (módulo `agenda`); barrido de retenciones vencidas cada 30 s | `e2` |
| Caché de cupos libres | Redis `cache-cupos`; clave `cupos-libres:{medicoId}:{fecha}`, cabecera `X-Cache`; los reportes usan la réplica (`MEDICITA_REPLICA_URL`) | búsqueda de cupos (`X-Cache: HIT/MISS`) |
| Autorización por consentimiento y auditoría | Módulo `acceso` (`co.edu.ucc.medicita.acceso`): política de consentimiento, claim `institucion` del token, `acceso.bitacora_acceso` con trigger de sólo inserción | `e3` |
| Intermediario y puerto-adaptador | Exchange `medicita.citas` (RabbitMQ); puerto `CanalNotificacion` con `WhatsAppAdaptador`, `SmsAdaptador` y `CorreoAdaptador` en `co.edu.ucc.medicita.notificaciones` | `respaldo-canal` |
| Límite de tasa y trazas correlacionadas | Zonas `api` y `reservas` y log JSON en `infra/gateway/nginx.conf`; `X-Correlation-Id` → MDC `correlacionId` → columna `correlacion_id` de la outbox → cabecera AMQP `x-correlation-id` | `traza` (y la limitación de tasa en `e2`) |
| Outbox transaccional y relé (ADR-002) | `agenda.outbox` y relé en el módulo `agenda`; alarma R5 (`medicita.outbox.pendiente.antiguedad.segundos`, `GET /api/agenda/operacion/outbox`) | `broker-caido` |
| Fitness function del estilo (ADR-001) | `ModularidadTest` y `PropiedadDeTablasTest` en `servicios/agendamiento/` | `mvn package` / `docker compose build agendamiento-1` |

## Solución de problemas

- **El puerto 443 está ocupado.** Arranque con otro puerto: `MEDICITA_HTTPS_PORT=8443 docker compose up -d --build` (PowerShell: `$env:MEDICITA_HTTPS_PORT=8443; docker compose up -d --build`) y use https://localhost:8443. Los escenarios no se ven afectados (usan la red interna).
- **El puerto 8025 (Mailpit) está ocupado.** Publique la interfaz de Mailpit en otro puerto: `MEDICITA_CORREO_UI_PORT=8026 docker compose up -d --build` y abra http://localhost:8026.
- **Se detuvo o recreó una instancia de la API.** No hace falta recargar el gateway: NGINX re-resuelve `agendamiento-1` y `agendamiento-2` con el DNS de Docker (`resolve`) y la instancia vuelve a recibir tráfico en unos 10 s.
- **`NET::ERR_CERT_AUTHORITY_INVALID` / «La conexión no es privada».** Falta confiar en la autoridad local: ejecute `infra\gateway\confiar-certificado.ps1` (ver [URLs](#urls)) y reinicie el navegador. Si borró `infra/gateway/certs/`, el gateway genera una autoridad nueva al arrancar: vuelva a ejecutar el script (reemplaza la anterior).
- **Algún servicio queda en `starting` o `unhealthy`.** Revise `docker compose ps` y `docker compose logs <servicio>`. Keycloak, Patroni y RabbitMQ (3 nodos) son los más lentos en la primera vez; las dependencias esperan por ellos. Si Docker Desktop tiene menos de 8 GB de RAM, los contenedores pueden reiniciarse por falta de memoria.
- **Un escenario falla porque «no hay cupos».** Los horarios se publican para los próximos 21 días calendario (cupos sólo de lunes a viernes) en el primer arranque. Si el stack lleva varias semanas arriba o se agotaron los cupos, reinicie los datos (`docker compose down -v`).
- **Los escenarios dicen que no tienen acceso a Docker.** Ejecútelos con `docker compose run --rm escenarios <nombre>` (el perfil monta `/var/run/docker.sock`).
- **Tras `e1` la BD tarda en estar sana.** El escenario reinicia ambos nodos de Patroni y espera a que estén `healthy`; el nodo detenido vuelve como réplica.

## Apagar

```bash
docker compose down        # detiene y elimina los contenedores; conserva los datos
docker compose down -v     # además borra los volúmenes: reinicia los datos de todo el sistema
```
