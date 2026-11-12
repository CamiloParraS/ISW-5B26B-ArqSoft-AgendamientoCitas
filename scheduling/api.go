// Package scheduling is the Scheduling module's public interface (report: Agenda).
// cmd/clinic cannot reach scheduling/internal, so New is the only place that
// knows this module's infrastructure.
package scheduling

import (
	"context"
	"net/http"
	"time"

	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/internal/platform/httpx"
	"clinic/scheduling/internal/application"
	"clinic/scheduling/internal/infrastructure"
	"clinic/scheduling/internal/presentation"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Public types. They are aliases, so callers never see table rows.
type (
	Slot        = application.Slot
	Appointment = application.Appointment
	Patient     = application.Patient
	Doctor      = application.Doctor
	BookResult  = application.BookResult
)

var (
	ErrSlotTaken = application.ErrSlotTaken
	ErrTooLate   = application.ErrTooLate
)

// Event types this module publishes.
const (
	EventAppointmentBooked   = "AppointmentBooked"
	EventAppointmentCanceled = "AppointmentCanceled"
	EventNoShowRecorded      = "NoShowRecorded"
)

type Deps struct {
	DB    *pgxpool.Pool // connected as scheduling_app
	Clock clock.Clock
	Bus   *events.Bus
	Auth  httpx.Authenticator
}

type Module struct {
	svc      *application.Service
	bus      *events.Bus
	handlers presentation.Handlers
}

func New(d Deps) *Module {
	svc := application.NewService(infrastructure.Repo{DB: d.DB}, d.Clock)
	return &Module{svc: svc, bus: d.Bus, handlers: presentation.Handlers{Svc: svc, Auth: d.Auth}}
}

func (m *Module) Routes(mux *http.ServeMux) { m.handlers.Routes(mux) }

// Book is the public booking entry point (the E-1 fitness test calls it with 50 goroutines).
func (m *Module) Book(ctx context.Context, patientID, slotID, idempotencyKey string) (BookResult, error) {
	return m.svc.Book(ctx, patientID, slotID, idempotencyKey)
}

func (m *Module) Cancel(ctx context.Context, patientID, appointmentID string) (Appointment, error) {
	return m.svc.Cancel(ctx, application.Actor{Role: "patient", SubjectID: patientID}, appointmentID)
}

func (m *Module) CheckIn(ctx context.Context, doctorID, appointmentID string) (Appointment, error) {
	return m.svc.CheckIn(ctx, application.Actor{Role: "doctor", SubjectID: doctorID}, appointmentID)
}

func (m *Module) OpenSlot(ctx context.Context, doctorID string, startsAt time.Time) (Slot, error) {
	return m.svc.OpenSlot(ctx, doctorID, startsAt)
}

func (m *Module) OpenSlots(ctx context.Context, doctorID string, limit int) ([]Slot, error) {
	return m.svc.OpenSlots(ctx, application.SlotFilter{DoctorID: doctorID, Limit: limit})
}

func (m *Module) Appointments(ctx context.Context) ([]Appointment, error) {
	return m.svc.List(ctx, application.Actor{Role: "staff"})
}

func (m *Module) Patients(ctx context.Context) ([]Patient, error) { return m.svc.Patients(ctx) }
func (m *Module) Doctors(ctx context.Context) ([]Doctor, error)   { return m.svc.Doctors(ctx) }

// Sweep marks overdue Appointments as No-shows and records the heartbeat.
func (m *Module) Sweep(ctx context.Context) (int, error) { return m.svc.Sweep(ctx) }

// DrainOutbox relays due events to the bus. Safe to run concurrently (SKIP LOCKED).
func (m *Module) DrainOutbox(ctx context.Context) (delivered, failed int, err error) {
	return m.svc.DrainOutbox(ctx, m.bus)
}

func (m *Module) PendingOutbox(ctx context.Context) (int, error) { return m.svc.PendingOutbox(ctx) }

// LastSweep feeds the reverse heartbeat on /health.
func (m *Module) LastSweep(ctx context.Context) (time.Time, bool, error) { return m.svc.LastSweep(ctx) }
