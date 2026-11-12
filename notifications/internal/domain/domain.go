// Package domain holds Notifications' model: Recipients, Messages, Delivery
// attempts, the Templates and the retry policy (T12, T13).
package domain

import (
	"context"
	"fmt"
	"math"
	"math/rand/v2"
	"time"
)

type Kind string

const (
	KindConfirmation Kind = "confirmation"
	KindReminder     Kind = "reminder"
	KindNoShow       Kind = "no_show"
	KindCancellation Kind = "cancellation"
)

type Status string

const (
	Pending  Status = "pending"
	Sent     Status = "sent"
	Canceled Status = "canceled"
	Failed   Status = "failed"
)

// Retry policy (T13).
const (
	CallTimeout   = 3 * time.Second
	GiveUpAfter   = 2 * time.Hour
	ReminderAhead = 24 * time.Hour
)

// Backoff returns the wait before retry n (1-based): 1, 2, 4, 8... minutes, ±20% jitter.
func Backoff(attempt int, r *rand.Rand) time.Duration {
	base := time.Duration(math.Pow(2, float64(attempt-1))) * time.Minute
	if base > 64*time.Minute {
		base = 64 * time.Minute
	}
	jitter := 0.8 + 0.4*r.Float64()
	return time.Duration(float64(base) * jitter)
}

type Recipient struct {
	PatientID string
	Name      string
	Channel   string
	Address   string
}

type Message struct {
	ID            string     `json:"id"`
	AppointmentID string     `json:"appointment_id"`
	PatientID     string     `json:"patient_id"`
	Kind          Kind       `json:"kind"`
	Channel       string     `json:"channel"`
	Address       string     `json:"address"`
	Body          string     `json:"body"`
	SendAt        time.Time  `json:"send_at"`
	Status        Status     `json:"status"`
	Attempts      int        `json:"attempts"`
	NextAttemptAt time.Time  `json:"next_attempt_at"`
	LastError     *string    `json:"last_error,omitempty"`
	SentAt        *time.Time `json:"sent_at,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
}

// AppointmentFacts is what Notifications learns from Scheduling's events.
type AppointmentFacts struct {
	AppointmentID string
	PatientID     string
	PatientName   string
	DoctorName    string
	Specialty     string
	StartsAt      time.Time
}

var bogota = func() *time.Location {
	if loc, err := time.LoadLocation("America/Bogota"); err == nil {
		return loc
	}
	return time.FixedZone("COT", -5*3600)
}()

func when(t time.Time) string { return t.In(bogota).Format("Mon 2 Jan, 15:04") }

func firstName(full string) string {
	for i, r := range full {
		if r == ' ' {
			return full[:i]
		}
	}
	return full
}

// Templates. Only reminder text ever leaves the system: no clinical data.
func Body(k Kind, f AppointmentFacts) string {
	switch k {
	case KindConfirmation:
		return fmt.Sprintf("Hi %s, your appointment with %s on %s is confirmed.", firstName(f.PatientName), f.DoctorName, when(f.StartsAt))
	case KindReminder:
		return fmt.Sprintf("Reminder: %s, you see %s tomorrow, %s. Reply C to cancel.", firstName(f.PatientName), f.DoctorName, when(f.StartsAt))
	case KindNoShow:
		return fmt.Sprintf("Hi %s, we missed you at your %s appointment with %s. Book again any time.", firstName(f.PatientName), when(f.StartsAt), f.DoctorName)
	case KindCancellation:
		return fmt.Sprintf("Hi %s, your appointment with %s on %s was canceled.", firstName(f.PatientName), f.DoctorName, when(f.StartsAt))
	}
	return ""
}

// Effect is what one event does to Notifications, applied once per event id.
type Effect struct {
	Enqueue          []Message
	CancelPendingFor string // appointment id whose pending Reminder/Confirmation stop
}

// Ports.
type Repository interface {
	Recipient(ctx context.Context, patientID string) (Recipient, error)
	// ApplyOnce runs the effect and records the event id in the inbox, in one
	// transaction. applied=false means the event was already processed.
	ApplyOnce(ctx context.Context, eventID string, now time.Time, e Effect) (applied bool, err error)
	// ClaimDue leases due pending Messages so concurrent deliverers skip them.
	ClaimDue(ctx context.Context, now time.Time, lease time.Duration, limit int) ([]Message, error)
	MarkSent(ctx context.Context, id string, now time.Time) error
	MarkAttemptFailed(ctx context.Context, id string, now time.Time, next time.Time, giveUp bool, reason string) error
	List(ctx context.Context, patientID string, limit int) ([]Message, error)
}

// Provider is the Messaging provider.
type Provider interface {
	Send(ctx context.Context, m Message) error
}
