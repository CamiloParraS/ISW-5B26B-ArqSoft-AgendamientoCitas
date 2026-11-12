---
title: Glossary and English naming
type: grilling
status: closed
blocked_by: []
assignee: Camilo Parra
---

## Question

The report is Spanish; the build is all-English. Fix the canonical mapping once: domain terms (cupo, cita, atencion, relacion de tratamiento, inasistencia, bandeja de salida, medico, paciente, personal administrativo), module names (Agenda, Atencion clinica, Notificaciones, Identidad, Auditoria), API paths (`/api/agenda/citas`, `/interno/tick`, `/api/clinica/pacientes/{id}/atenciones`), event names (CitaReservada, CitaCancelada), DB schema/role names, and the Go module name. Record it as a GLOSSARY.md so every later ticket and the code use the same words.

## Resolution

2026-11-11, grilled with the user, all recommendations accepted. The domain terms are in [GLOSSARY.md](../../GLOSSARY.md), each with its Spanish report term. Implementation names, which the glossary format excludes:

- Modules and Go packages: `scheduling` (Agenda), `clinical` (Atención clínica), `notifications`, `identity`, `audit`.
- Go module `clinic`; binary at `cmd/clinic`.
- External scheduler = **Ticker** (container `ticker`), which calls `POST /internal/tick`. Each Tick runs the **Sweep**. Report `/salud` = `GET /health`.
- API paths: `/api/<module>/<plural-noun>`, for example `POST /api/scheduling/appointments` and `GET /api/clinical/patients/{id}/encounters`.
- DB: schemas named after the modules; runtime login roles `<module>_app`.
- Role ids: `patient`, `doctor`, `staff`, `dpo`.
- Statuses: Slot `open|booked`; Appointment `confirmed|canceled|attended|no_show`.
- Reschedule = Cancellation + Book in one transaction; the new Appointment stores `rescheduled_from`.
- Treatment relationship holds while at least one Appointment between the Doctor and the Patient is not canceled.
- Events are past-tense `<Noun><Verb>` with American spelling: `AppointmentBooked`, `AppointmentCanceled`.
