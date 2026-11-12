// Package domain holds Scheduling's model and rules: Slots, Appointments,
// the 24 h Cancellation rule and the 15 min No-show rule. It never reads the
// system time and never imports infrastructure, net/http or pgx.
package domain

import (
	"context"
	"errors"
	"time"
)

type SlotStatus string

const (
	SlotOpen   SlotStatus = "open"
	SlotBooked SlotStatus = "booked"
)

type Status string

const (
	Confirmed Status = "confirmed"
	Canceled  Status = "canceled"
	Attended  Status = "attended"
	NoShow    Status = "no_show"
)

// Business rules (report section 1).
const (
	CancellationNotice = 24 * time.Hour   // cancel or reschedule at least 24 h before
	NoShowGrace        = 15 * time.Minute // no Check in within 15 min -> No-show
)

var (
	ErrSlotTaken        = errors.New("slot already taken")
	ErrSlotNotFound     = errors.New("slot not found")
	ErrSlotInPast       = errors.New("slot already started")
	ErrNotFound         = errors.New("appointment not found")
	ErrNotYours         = errors.New("appointment belongs to someone else")
	ErrTooLate          = errors.New("appointments can only be canceled or rescheduled at least 24 hours in advance")
	ErrNotConfirmed     = errors.New("only confirmed appointments can change")
	ErrKeyRequired      = errors.New("Idempotency-Key header is required")
	ErrDoctorNotFound   = errors.New("doctor not found")
	ErrSlotExists       = errors.New("the doctor already has a slot at that time")
)

type Doctor struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Specialty string `json:"specialty"`
}

type Patient struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type Slot struct {
	ID        string     `json:"id"`
	DoctorID  string     `json:"doctor_id"`
	Doctor    string     `json:"doctor"`
	Specialty string     `json:"specialty"`
	StartsAt  time.Time  `json:"starts_at"`
	Status    SlotStatus `json:"status"`
}

type Appointment struct {
	ID              string     `json:"id"`
	SlotID          string     `json:"slot_id"`
	PatientID       string     `json:"patient_id"`
	Patient         string     `json:"patient"`
	DoctorID        string     `json:"doctor_id"`
	Doctor          string     `json:"doctor"`
	Specialty       string     `json:"specialty"`
	StartsAt        time.Time  `json:"starts_at"`
	Status          Status     `json:"status"`
	RescheduledFrom *string    `json:"rescheduled_from,omitempty"`
	CheckedInAt     *time.Time `json:"checked_in_at,omitempty"`
	CreatedAt       time.Time  `json:"created_at"`
}

// CanChange applies the 24 h rule to a Cancellation or Reschedule.
func CanChange(a Appointment, now time.Time) error {
	if a.Status != Confirmed {
		return ErrNotConfirmed
	}
	if a.StartsAt.Sub(now) < CancellationNotice {
		return ErrTooLate
	}
	return nil
}

// NoShowCutoff: confirmed Appointments starting at or before this instant are No-shows.
func NoShowCutoff(now time.Time) time.Time { return now.Add(-NoShowGrace) }

// ------------------------------------------------------------------ events

const (
	EventAppointmentBooked   = "AppointmentBooked"
	EventAppointmentCanceled = "AppointmentCanceled"
	EventNoShowRecorded      = "NoShowRecorded"
)

type AppointmentBooked struct {
	AppointmentID   string    `json:"appointment_id"`
	SlotID          string    `json:"slot_id"`
	PatientID       string    `json:"patient_id"`
	PatientName     string    `json:"patient_name"`
	DoctorID        string    `json:"doctor_id"`
	DoctorName      string    `json:"doctor_name"`
	Specialty       string    `json:"specialty"`
	StartsAt        time.Time `json:"starts_at"`
	RescheduledFrom *string   `json:"rescheduled_from,omitempty"`
}

type AppointmentCanceled struct {
	AppointmentID string    `json:"appointment_id"`
	PatientID     string    `json:"patient_id"`
	PatientName   string    `json:"patient_name"`
	DoctorID      string    `json:"doctor_id"`
	DoctorName    string    `json:"doctor_name"`
	StartsAt      time.Time `json:"starts_at"`
	Reason        string    `json:"reason"` // canceled | rescheduled
}

type NoShowRecorded struct {
	AppointmentID string    `json:"appointment_id"`
	PatientID     string    `json:"patient_id"`
	PatientName   string    `json:"patient_name"`
	DoctorID      string    `json:"doctor_id"`
	DoctorName    string    `json:"doctor_name"`
	StartsAt      time.Time `json:"starts_at"`
}

func BookedEvent(a Appointment) AppointmentBooked {
	return AppointmentBooked{a.ID, a.SlotID, a.PatientID, a.Patient, a.DoctorID, a.Doctor, a.Specialty, a.StartsAt, a.RescheduledFrom}
}

func CanceledEvent(a Appointment, reason string) AppointmentCanceled {
	return AppointmentCanceled{a.ID, a.PatientID, a.Patient, a.DoctorID, a.Doctor, a.StartsAt, reason}
}

func NoShowEvent(a Appointment) NoShowRecorded {
	return NoShowRecorded{a.ID, a.PatientID, a.Patient, a.DoctorID, a.Doctor, a.StartsAt}
}

// ------------------------------------------------------------------- ports

// OutboxRecord is one undelivered event, as the relay sees it.
type OutboxRecord struct {
	EventID    string
	Type       string
	OccurredAt time.Time
	Payload    []byte
	Attempts   int
}

type SlotFilter struct {
	DoctorID string
	From     time.Time
	Limit    int
}

// Repository is Scheduling's storage port. Each method that changes state is
// one transaction, and writes its events to the outbox in that same transaction (T4).
type Repository interface {
	Doctors(ctx context.Context) ([]Doctor, error)
	Patients(ctx context.Context) ([]Patient, error)
	OpenSlots(ctx context.Context, f SlotFilter) ([]Slot, error)
	Slot(ctx context.Context, id string) (Slot, error)
	AddSlot(ctx context.Context, doctorID string, startsAt time.Time) (Slot, error)

	// Book: idempotency key (T3) + conditional write on the Slot (T1) + insert
	// guarded by the partial unique index (T2) + outbox (T4). created=false on replay.
	Book(ctx context.Context, patientID, slotID, key string, now time.Time) (a Appointment, created bool, err error)
	Get(ctx context.Context, id string) (Appointment, error)
	ListByPatient(ctx context.Context, patientID string) ([]Appointment, error)
	ListByDoctor(ctx context.Context, doctorID string) ([]Appointment, error)
	ListAll(ctx context.Context, limit int) ([]Appointment, error)

	// Cancel releases the Slot. Conditional on status = confirmed.
	Cancel(ctx context.Context, id string, now time.Time) (Appointment, error)
	// Reschedule = Cancel + Book in one transaction.
	Reschedule(ctx context.Context, id, newSlotID string, now time.Time) (Appointment, error)
	CheckIn(ctx context.Context, id string, now time.Time) (Appointment, error)

	// MarkNoShows turns every overdue confirmed Appointment into a No-show with a
	// conditional write, so running it twice changes nothing the second time (T14).
	MarkNoShows(ctx context.Context, cutoff, now time.Time) ([]Appointment, error)
	RecordSweep(ctx context.Context, at time.Time, noShows int) error
	LastSweep(ctx context.Context) (at time.Time, ok bool, err error)

	// RelayOutbox claims due outbox rows (SKIP LOCKED), hands each to publish,
	// and marks it delivered or schedules a retry.
	RelayOutbox(ctx context.Context, now time.Time, limit int, publish func(context.Context, OutboxRecord) error) (delivered, failed int, err error)
	OutboxStats(ctx context.Context) (pending int, err error)
}
