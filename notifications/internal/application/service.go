// Package application holds Notifications' use cases: react to Scheduling's
// events and deliver due Messages with retries.
package application

import (
	"context"
	"encoding/json"
	"errors"
	"math/rand/v2"
	"sync"
	"time"

	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/notifications/internal/domain"
)

type Message = domain.Message

// Event types consumed (Scheduling's published language, decoded into our own shapes).
const (
	evBooked   = "AppointmentBooked"
	evCanceled = "AppointmentCanceled"
	evNoShow   = "NoShowRecorded"
)

type appointmentPayload struct {
	AppointmentID string    `json:"appointment_id"`
	PatientID     string    `json:"patient_id"`
	PatientName   string    `json:"patient_name"`
	DoctorName    string    `json:"doctor_name"`
	Specialty     string    `json:"specialty"`
	StartsAt      time.Time `json:"starts_at"`
	Reason        string    `json:"reason"`
}

type Service struct {
	repo     domain.Repository
	provider domain.Provider
	clock    clock.Clock
	mu       sync.Mutex
	rnd      *rand.Rand
}

func NewService(repo domain.Repository, p domain.Provider, c clock.Clock) *Service {
	return &Service{repo: repo, provider: p, clock: c, rnd: rand.New(rand.NewPCG(uint64(time.Now().UnixNano()), 7))}
}

// Subscribe registers this module's consumers on the bus.
func (s *Service) Subscribe(bus *events.Bus) {
	bus.Subscribe(evBooked, "notifications", s.handle)
	bus.Subscribe(evCanceled, "notifications", s.handle)
	bus.Subscribe(evNoShow, "notifications", s.handle)
}

func (s *Service) handle(ctx context.Context, e events.Event) error {
	var p appointmentPayload
	if err := json.Unmarshal(e.Payload, &p); err != nil {
		return err
	}
	facts := domain.AppointmentFacts{AppointmentID: p.AppointmentID, PatientID: p.PatientID, PatientName: p.PatientName,
		DoctorName: p.DoctorName, Specialty: p.Specialty, StartsAt: p.StartsAt}
	now := s.clock.Now()
	rcp, err := s.repo.Recipient(ctx, p.PatientID)
	if err != nil {
		return err
	}
	msg := func(k domain.Kind, sendAt time.Time) domain.Message {
		return domain.Message{AppointmentID: p.AppointmentID, PatientID: p.PatientID, Kind: k,
			Channel: rcp.Channel, Address: rcp.Address, Body: domain.Body(k, facts), SendAt: sendAt, NextAttemptAt: sendAt}
	}
	var eff domain.Effect
	switch e.Type {
	case evBooked:
		eff.Enqueue = []domain.Message{msg(domain.KindConfirmation, now)}
		reminderAt := p.StartsAt.Add(-domain.ReminderAhead)
		if reminderAt.After(now) {
			eff.Enqueue = append(eff.Enqueue, msg(domain.KindReminder, reminderAt))
		}
	case evCanceled:
		eff.CancelPendingFor = p.AppointmentID
		if p.Reason != "rescheduled" { // the new booking's Confirmation already tells them
			eff.Enqueue = []domain.Message{msg(domain.KindCancellation, now)}
		}
	case evNoShow:
		eff.Enqueue = []domain.Message{msg(domain.KindNoShow, now)}
	default:
		return nil
	}
	_, err = s.repo.ApplyOnce(ctx, e.ID, now, eff)
	return err
}

// DeliveryReport summarises one delivery pass.
type DeliveryReport struct {
	Sent    int `json:"sent"`
	Retried int `json:"retried"`
	Failed  int `json:"failed"`
}

// DeliverDue sends every due Message. A Provider failure never touches the
// Appointment (T12); it schedules a retry with backoff, giving up after 2 h (T13).
func (s *Service) DeliverDue(ctx context.Context) (DeliveryReport, error) {
	var rep DeliveryReport
	now := s.clock.Now()
	due, err := s.repo.ClaimDue(ctx, now, time.Minute, 50)
	if err != nil {
		return rep, err
	}
	for _, m := range due {
		callCtx, cancel := context.WithTimeout(ctx, domain.CallTimeout)
		sendErr := s.provider.Send(callCtx, m)
		cancel()
		at := s.clock.Now()
		if sendErr == nil {
			if err := s.repo.MarkSent(ctx, m.ID, at); err != nil {
				return rep, err
			}
			rep.Sent++
			continue
		}
		s.mu.Lock()
		wait := domain.Backoff(m.Attempts+1, s.rnd)
		s.mu.Unlock()
		giveUp := at.Add(wait).Sub(m.SendAt) > domain.GiveUpAfter
		if err := s.repo.MarkAttemptFailed(ctx, m.ID, at, at.Add(wait), giveUp, sendErr.Error()); err != nil {
			return rep, errors.Join(sendErr, err)
		}
		if giveUp {
			rep.Failed++
		} else {
			rep.Retried++
		}
	}
	return rep, nil
}

func (s *Service) List(ctx context.Context, patientID string) ([]Message, error) {
	return s.repo.List(ctx, patientID, 100)
}
