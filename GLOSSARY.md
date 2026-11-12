# Clinic appointment scheduling

A clinic in Colombia with several specialties: patients book, reschedule and cancel appointments; doctors see their schedule and record what happened; clinical data is visible only to doctors who treat the patient. Each entry carries the Spanish term used in the architecture report (_Report_) so code and report can be read side by side.

## Actors

**Patient**:
A person who books appointments and receives reminders.
_Report_: paciente
_Avoid_: client, user (a User is an Identity concept)

**Doctor**:
A clinician who has a calendar of Slots and may hold Treatment relationships with Patients.
_Report_: médico
_Avoid_: physician, practitioner

**Specialty**:
A branch of medicine a Doctor practices, such as Cardiology or Orthopedics.
_Report_: especialidad

**Staff**:
Clinic administrative personnel who define schedules, open Slots and run reports; never see Diagnoses or Prescriptions.
_Report_: personal administrativo
_Avoid_: admin, administrator

**Data Protection Officer**:
The person who reviews the Access log.
_Report_: responsable de protección de datos
_Avoid_: auditor

**Messaging provider**:
The external service that delivers SMS or email; it only ever receives reminder text, never clinical data.
_Report_: proveedor de mensajería
_Avoid_: SMS gateway, notifier

**Ticker**:
The external actor that wakes the system periodically by sending a Tick.
_Report_: programador de tareas (externo)
_Avoid_: scheduler, cron

## Scheduling

**Slot**:
One Doctor, one date, one start time; holds at most one confirmed Appointment. Either open or booked.
_Report_: cupo
_Avoid_: time slot, availability, opening

**Appointment**:
A Patient's booking of a Slot. Its status is confirmed, canceled, attended or no-show.
_Report_: cita
_Avoid_: booking, reservation, visit

**Book**:
To create a confirmed Appointment on an open Slot.
_Report_: reservar
_Avoid_: reserve, schedule

**Cancellation**:
Ending a confirmed Appointment at least 24 hours before its start; the Slot becomes open again immediately.
_Report_: cancelación
_Avoid_: deletion

**Reschedule**:
A Cancellation and a Book done together; the new Appointment remembers the one it replaced.
_Report_: reprogramar
_Avoid_: move, edit appointment

**Check in**:
The Doctor marking that the Patient attended; turns the Appointment to attended.
_Report_: marcar asistencia
_Avoid_: attendance, mark present

**No-show**:
The status of an Appointment nobody checked in within 15 minutes of its start.
_Report_: inasistencia
_Avoid_: absence, missed appointment

**Tick**:
One wake-up call from the Ticker.
_Report_: llamada del programador
_Avoid_: ping, cron run

**Sweep**:
The work a Tick triggers: everything overdue since the last Sweep is processed once.
_Report_: barrido (de vencimientos)
_Avoid_: job, batch

## Clinical

**Encounter**:
What happened in a consultation: the Diagnoses and Prescriptions a Doctor recorded for a Patient.
_Report_: atención
_Avoid_: visit, consultation, appointment

**Diagnosis**:
A Doctor's clinical finding within an Encounter. Sensitive data.
_Report_: diagnóstico

**Prescription**:
A treatment order within an Encounter. Sensitive data.
_Report_: receta

**Clinical record**:
All Encounters of one Patient; never deleted, corrections are added as new versions.
_Report_: historia clínica
_Avoid_: medical history, chart

**Treatment relationship**:
The link that lets a Doctor read a Patient's Clinical record. It holds while at least one Appointment between them is not canceled.
_Report_: relación de tratamiento
_Avoid_: care relationship, access grant

## Notifications

**Recipient**:
A Patient as Notifications sees them: a preferred channel and an address.
_Report_: destinatario
_Avoid_: patient (inside Notifications)

**Confirmation**:
The Message sent to a Recipient as soon as an Appointment is booked.
_Report_: (implícito en el guion de la demostración, paso 3)

**Reminder**:
The Message sent to a Recipient 24 hours before an Appointment.
_Report_: recordatorio

**Message**:
One text for one Recipient over one Channel.
_Report_: mensaje
_Avoid_: notification, alert

**Channel**:
The way a Message travels: SMS or email.
_Report_: canal

**Delivery attempt**:
One try at handing a Message to the Messaging provider.
_Report_: intento (de entrega)
_Avoid_: retry, send

**Template**:
The wording a Message is built from.
_Report_: plantilla

## Identity

**User**:
A login with exactly one role (Patient, Doctor, Staff or Data Protection Officer); refers to the person, is not the person.
_Report_: usuario
_Avoid_: account

**Credential**:
What a User proves identity with.
_Report_: credencial

**Access token**:
The signed, expiring proof of identity and role that Identity issues and every other module verifies.
_Report_: token
_Avoid_: session, JWT

**Tick token**:
The shared secret the Ticker presents on every Tick.
_Report_: token (del punto de entrada interno)

## Audit

**Access log**:
The append-only record of who read or changed clinical data; never edited, never deleted.
_Report_: bitácora (de accesos)
_Avoid_: audit trail, audit log

**Access entry**:
One line of the Access log: who, which resource, when, from where, and whether it was allowed or denied.
_Report_: evento de acceso
_Avoid_: audit event

## Domain events

Named in the past tense as `<Noun><Verb>`, American spelling.

**AppointmentBooked**:
An Appointment was confirmed on a Slot.
_Report_: CitaReservada

**AppointmentCanceled**:
An Appointment was canceled and its Slot is open again.
_Report_: CitaCancelada
