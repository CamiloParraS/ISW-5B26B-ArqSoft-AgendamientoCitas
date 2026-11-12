// Package domain holds Audit's model: the append-only Access log (T8).
package domain

import (
	"context"
	"errors"
	"time"
)

// Entry is one Access entry: who, which resource, when, from where, allowed or not.
type Entry struct {
	ID        int64     `json:"id"`
	At        time.Time `json:"at"`
	UserID    string    `json:"user_id"`
	UserName  string    `json:"user_name"`
	Role      string    `json:"role"`
	Action    string    `json:"action"` // read | write
	Resource  string    `json:"resource"`
	PatientID string    `json:"patient_id,omitempty"`
	Allowed   bool      `json:"allowed"`
	Reason    string    `json:"reason"`
	Source    string    `json:"source"`
}

var ErrIncomplete = errors.New("access entry needs user, action and resource")

func (e Entry) Validate() error {
	if e.UserID == "" || e.Action == "" || e.Resource == "" {
		return ErrIncomplete
	}
	return nil
}

// Log is the storage port. It has no update and no delete, on purpose.
type Log interface {
	Append(ctx context.Context, e Entry) error
	Recent(ctx context.Context, limit int) ([]Entry, error)
}
