// Package application holds Scheduling's use cases. Presentation talks only to
// this package; the domain types it needs are re-exported here.
package application

import (
	"context"
	"errors"
	"time"

	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/scheduling/internal/domain"
)

type (
	Doctor      = domain.Doctor
	Patient     = domain.Patient
	Slot        = domain.Slot
	Appointment = domain.Appointment
	SlotFilter  = domain.SlotFilter
)

var (
	ErrSlotTaken      = domain.ErrSlotTaken
	ErrSlotNotFound   = domain.ErrSlotNotFound
	ErrSlotInPast     = domain.ErrSlotInPast
	ErrNotFound       = domain.ErrNotFound
	ErrNotYours       = domain.ErrNotYours
	ErrTooLate        = domain.ErrTooLate
	ErrNotConfirmed   = domain.ErrNotConfirmed
	ErrKeyRequired    = domain.ErrKeyRequired
	ErrDoctorNotFound = domain.ErrDoctorNotFound
	ErrSlotExists     = domain.ErrSlotExists
)

// Actor is who asks, reduced to what Scheduling's rules need.
type Actor struct {
	Role      string // patient | doctor | staff | dpo
	SubjectID string
}

type Service struct {
	repo  domain.Repository
	clock clock.Clock
}

func NewService(repo domain.Repository, c clock.Clock) *Service {
	return &Service{repo: repo, clock: c}
}

func (s *Service) Now() time.Time { return s.clock.Now() }

func (s *Service) Doctors(ctx context.Context) ([]Doctor, error)   { return s.repo.Doctors(ctx) }
func (s *Service) Patients(ctx context.Context) ([]Patient, error) { return s.repo.Patients(ctx) }

func (s *Service) OpenSlots(ctx context.Context, f SlotFilter) ([]Slot, error) {
	if f.From.IsZero() {
		f.From = s.clock.Now()
	}
	if f.Limit <= 0 || f.Limit > 200 {
		f.Limit = 60
	}
	return s.repo.OpenSlots(ctx, f)
}

func (s *Service) OpenSlot(ctx context.Context, doctorID string, startsAt time.Time) (Slot, error) {
	if !startsAt.After(s.clock.Now()) {
		return Slot{}, ErrSlotInPast
	}
	return s.repo.AddSlot(ctx, doctorID, startsAt.UTC().Truncate(time.Minute))
}

// BookResult carries alternatives when the Slot was taken (E-1 response).
type BookResult struct {
	Appointment  Appointment
	Created      bool
	Alternatives []Slot
}

// Book is ReservarCita from the report's ADD iteration: one transaction with the
// conditional write, the Appointment and the outbox event.
func (s *Service) Book(ctx context.Context, patientID, slotID, key string) (BookResult, error) {
	if key == "" {
		return BookResult{}, ErrKeyRequired
	}
	now := s.clock.Now()
	a, created, err := s.repo.Book(ctx, patientID, slotID, key, now)
	if errors.Is(err, ErrSlotTaken) {
		alts, _ := s.alternatives(ctx, slotID)
		return BookResult{Alternatives: alts}, err
	}
	return BookResult{Appointment: a, Created: created}, err
}

// alternatives: the three next open Slots of the same Doctor, else of anyone.
func (s *Service) alternatives(ctx context.Context, slotID string) ([]Slot, error) {
	taken, err := s.repo.Slot(ctx, slotID)
	if err != nil {
		return nil, err
	}
	alts, err := s.repo.OpenSlots(ctx, SlotFilter{DoctorID: taken.DoctorID, From: maxTime(taken.StartsAt, s.clock.Now()), Limit: 3})
	if err == nil && len(alts) == 0 {
		alts, err = s.repo.OpenSlots(ctx, SlotFilter{From: s.clock.Now(), Limit: 3})
	}
	return alts, err
}

func (s *Service) Get(ctx context.Context, actor Actor, id string) (Appointment, error) {
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return a, err
	}
	return a, authorize(actor, a)
}

func (s *Service) List(ctx context.Context, actor Actor) ([]Appointment, error) {
	switch actor.Role {
	case "patient":
		return s.repo.ListByPatient(ctx, actor.SubjectID)
	case "doctor":
		return s.repo.ListByDoctor(ctx, actor.SubjectID)
	default:
		return s.repo.ListAll(ctx, 200)
	}
}

func (s *Service) Cancel(ctx context.Context, actor Actor, id string) (Appointment, error) {
	a, err := s.Get(ctx, actor, id)
	if err != nil {
		return a, err
	}
	now := s.clock.Now()
	if err := domain.CanChange(a, now); err != nil {
		return a, err
	}
	return s.repo.Cancel(ctx, id, now)
}

func (s *Service) Reschedule(ctx context.Context, actor Actor, id, newSlotID string) (Appointment, error) {
	a, err := s.Get(ctx, actor, id)
	if err != nil {
		return a, err
	}
	now := s.clock.Now()
	if err := domain.CanChange(a, now); err != nil {
		return a, err
	}
	return s.repo.Reschedule(ctx, id, newSlotID, now)
}

func (s *Service) CheckIn(ctx context.Context, actor Actor, id string) (Appointment, error) {
	a, err := s.Get(ctx, actor, id)
	if err != nil {
		return a, err
	}
	if a.Status != domain.Confirmed {
		return a, ErrNotConfirmed
	}
	return s.repo.CheckIn(ctx, id, s.clock.Now())
}

// Sweep marks every overdue Appointment as a No-show once and records the heartbeat.
func (s *Service) Sweep(ctx context.Context) (int, error) {
	now := s.clock.Now()
	marked, err := s.repo.MarkNoShows(ctx, domain.NoShowCutoff(now), now)
	if err != nil {
		return 0, err
	}
	return len(marked), s.repo.RecordSweep(ctx, now, len(marked))
}

func (s *Service) LastSweep(ctx context.Context) (time.Time, bool, error) { return s.repo.LastSweep(ctx) }

// DrainOutbox relays due outbox rows to the bus.
func (s *Service) DrainOutbox(ctx context.Context, bus *events.Bus) (int, int, error) {
	return s.repo.RelayOutbox(ctx, s.clock.Now(), 100, func(ctx context.Context, r domain.OutboxRecord) error {
		return bus.Publish(ctx, events.Event{ID: r.EventID, Type: r.Type, OccurredAt: r.OccurredAt, Payload: r.Payload})
	})
}

func (s *Service) PendingOutbox(ctx context.Context) (int, error) { return s.repo.OutboxStats(ctx) }

func authorize(actor Actor, a Appointment) error {
	switch actor.Role {
	case "staff":
		return nil
	case "patient":
		if a.PatientID == actor.SubjectID {
			return nil
		}
	case "doctor":
		if a.DoctorID == actor.SubjectID {
			return nil
		}
	}
	return ErrNotYours
}

func maxTime(a, b time.Time) time.Time {
	if a.After(b) {
		return a
	}
	return b
}
