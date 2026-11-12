// Package application holds Audit's use cases.
package application

import (
	"context"

	"clinic/audit/internal/domain"
	"clinic/internal/platform/clock"
)

type Entry = domain.Entry

type Service struct {
	log   domain.Log
	clock clock.Clock
}

func NewService(l domain.Log, c clock.Clock) *Service { return &Service{log: l, clock: c} }

// Record appends one Access entry. Callers must treat an error as "do not proceed".
func (s *Service) Record(ctx context.Context, e Entry) error {
	if e.At.IsZero() {
		e.At = s.clock.Now()
	}
	if err := e.Validate(); err != nil {
		return err
	}
	return s.log.Append(ctx, e)
}

func (s *Service) Recent(ctx context.Context, limit int) ([]Entry, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	return s.log.Recent(ctx, limit)
}
