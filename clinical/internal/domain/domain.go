// Package domain holds Clinical's model: Encounters, the Treatment relationship,
// and the rule deciding who may read a Clinical record (T6).
package domain

import (
	"context"
	"errors"
	"time"
)

var (
	ErrForbidden        = errors.New("not allowed to access this clinical record")
	ErrAuditUnavailable = errors.New("access log unavailable; clinical data is not served without an access entry")
	ErrInvalid          = errors.New("diagnosis and prescription are required")
)

// Encounter is what happened in one consultation. Diagnosis and Prescription
// are sensitive (Ley 1581, art. 5) and stored encrypted.
type Encounter struct {
	ID           string    `json:"id"`
	PatientID    string    `json:"patient_id"`
	DoctorID     string    `json:"doctor_id"`
	DoctorName   string    `json:"doctor_name"`
	OccurredAt   time.Time `json:"occurred_at"`
	Diagnosis    string    `json:"diagnosis"`
	Prescription string    `json:"prescription"`
	Version      int       `json:"version"`
	Supersedes   *string   `json:"supersedes,omitempty"`
}

// Reader is whoever asks for a Clinical record.
type Reader struct {
	UserID    string
	UserName  string
	Role      string
	SubjectID string
	Source    string
}

// Decide applies T6: role plus Treatment relationship. It never looks at whether
// the Patient has records, so a denial reveals nothing about them.
func Decide(r Reader, patientID, action string, hasRelationship bool) (allowed bool, reason string) {
	if action == "write" && r.Role != "doctor" {
		return false, "only doctors record encounters"
	}
	switch r.Role {
	case "doctor":
		if hasRelationship {
			return true, "treatment relationship"
		}
		return false, "no treatment relationship"
	case "patient":
		if r.SubjectID == patientID {
			return true, "own record"
		}
		return false, "another patient's record"
	default:
		return false, "role " + r.Role + " cannot read clinical data"
	}
}

// AccessEntry is what Clinical asks Audit to record.
type AccessEntry struct {
	At        time.Time
	Reader    Reader
	Action    string
	Resource  string
	PatientID string
	Allowed   bool
	Reason    string
}

// Relationship is one Appointment's contribution to a Treatment relationship.
// The relationship holds while at least one of them exists.
type Relationship struct {
	AppointmentID string
	DoctorID      string
	PatientID     string
}

// Ports.
type Repository interface {
	HasRelationship(ctx context.Context, doctorID, patientID string) (bool, error)
	Encounters(ctx context.Context, patientID string) ([]Encounter, error)
	AddEncounter(ctx context.Context, e Encounter) (Encounter, error)
	// ApplyOnce records the event in the inbox and applies the change, in one transaction.
	ApplyOnce(ctx context.Context, eventID string, now time.Time, add *Relationship, removeAppointmentID string) (bool, error)
}

type AccessRecorder interface {
	Record(ctx context.Context, e AccessEntry) error
}
