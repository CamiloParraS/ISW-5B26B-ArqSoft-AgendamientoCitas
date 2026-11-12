// Package application holds Clinical's use cases: read and write a Clinical
// record (audited, fail closed) and keep Treatment relationships in sync.
package application

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"clinic/clinical/internal/domain"
	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
)

type (
	Encounter   = domain.Encounter
	Reader      = domain.Reader
	AccessEntry = domain.AccessEntry
)

var (
	ErrForbidden        = domain.ErrForbidden
	ErrAuditUnavailable = domain.ErrAuditUnavailable
	ErrInvalid          = domain.ErrInvalid
)

type Service struct {
	repo  domain.Repository
	audit domain.AccessRecorder
	clock clock.Clock
}

func NewService(repo domain.Repository, audit domain.AccessRecorder, c clock.Clock) *Service {
	return &Service{repo: repo, audit: audit, clock: c}
}

// authorize decides and writes the Access entry BEFORE anything is returned
// (amendment 3). If the entry cannot be written, nothing is served.
func (s *Service) authorize(ctx context.Context, r Reader, patientID, action string) error {
	has := false
	if r.Role == "doctor" {
		var err error
		if has, err = s.repo.HasRelationship(ctx, r.SubjectID, patientID); err != nil {
			return err
		}
	}
	allowed, reason := domain.Decide(r, patientID, action, has)
	entry := domain.AccessEntry{At: s.clock.Now(), Reader: r, Action: action,
		Resource: "clinical/patients/" + patientID + "/encounters", PatientID: patientID, Allowed: allowed, Reason: reason}
	if err := s.audit.Record(ctx, entry); err != nil {
		return ErrAuditUnavailable
	}
	if !allowed {
		return ErrForbidden
	}
	return nil
}

func (s *Service) ReadRecord(ctx context.Context, r Reader, patientID string) ([]Encounter, error) {
	if err := s.authorize(ctx, r, patientID, "read"); err != nil {
		return nil, err
	}
	return s.repo.Encounters(ctx, patientID)
}

// RecordEncounter is the Doctor registering Diagnosis and Prescription.
func (s *Service) RecordEncounter(ctx context.Context, r Reader, patientID, diagnosis, prescription string) (Encounter, error) {
	if strings.TrimSpace(diagnosis) == "" || strings.TrimSpace(prescription) == "" {
		return Encounter{}, ErrInvalid
	}
	if err := s.authorize(ctx, r, patientID, "write"); err != nil {
		return Encounter{}, err
	}
	return s.repo.AddEncounter(ctx, Encounter{PatientID: patientID, DoctorID: r.SubjectID, DoctorName: r.UserName,
		OccurredAt: s.clock.Now(), Diagnosis: diagnosis, Prescription: prescription, Version: 1})
}

// ------------------------------------------------------------- consumers

type appointmentPayload struct {
	AppointmentID string `json:"appointment_id"`
	PatientID     string `json:"patient_id"`
	DoctorID      string `json:"doctor_id"`
}

// Subscribe keeps Treatment relationships fed by Scheduling's events, so an
// authorization never has to ask Scheduling anything (report section 6).
func (s *Service) Subscribe(bus *events.Bus) {
	bus.Subscribe("AppointmentBooked", "clinical", func(ctx context.Context, e events.Event) error {
		var p appointmentPayload
		if err := json.Unmarshal(e.Payload, &p); err != nil {
			return err
		}
		_, err := s.repo.ApplyOnce(ctx, e.ID, s.now(), &domain.Relationship{AppointmentID: p.AppointmentID, DoctorID: p.DoctorID, PatientID: p.PatientID}, "")
		return err
	})
	bus.Subscribe("AppointmentCanceled", "clinical", func(ctx context.Context, e events.Event) error {
		var p appointmentPayload
		if err := json.Unmarshal(e.Payload, &p); err != nil {
			return err
		}
		_, err := s.repo.ApplyOnce(ctx, e.ID, s.now(), nil, p.AppointmentID)
		return err
	})
}

func (s *Service) now() time.Time { return s.clock.Now() }
