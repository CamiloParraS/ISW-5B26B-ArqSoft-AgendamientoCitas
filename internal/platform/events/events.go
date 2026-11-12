// Package events is the in-process event dispatcher. Producers write events to
// their own outbox; a relay publishes them here; consumers subscribe by type.
// Delivery is at-least-once, so every consumer must be idempotent (inbox table).
package events

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"clinic/internal/platform/ids"
)

// Event is the envelope that travels from an outbox to the consumers.
// Payloads carry ids, names and times only, never clinical data.
type Event struct {
	ID         string          `json:"id"`
	Type       string          `json:"type"`
	OccurredAt time.Time       `json:"occurred_at"`
	Payload    json.RawMessage `json:"payload"`
}

// New builds an event with a fresh id.
func New(eventType string, at time.Time, payload any) (Event, error) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return Event{}, err
	}
	return Event{ID: ids.New(), Type: eventType, OccurredAt: at.UTC(), Payload: raw}, nil
}

// Handler consumes one event. Returning an error makes the relay retry the event later.
type Handler func(ctx context.Context, e Event) error

type subscription struct {
	consumer string
	handle   Handler
}

// Bus routes events to subscribers.
type Bus struct {
	mu   sync.RWMutex
	subs map[string][]subscription
}

func NewBus() *Bus { return &Bus{subs: map[string][]subscription{}} }

// Subscribe registers a consumer for one event type.
func (b *Bus) Subscribe(eventType, consumer string, h Handler) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.subs[eventType] = append(b.subs[eventType], subscription{consumer, h})
}

// Publish delivers the event to every subscriber. All of them run even if one fails;
// the joined error tells the relay to retry, and the inboxes absorb the repeats.
func (b *Bus) Publish(ctx context.Context, e Event) error {
	b.mu.RLock()
	subs := append([]subscription(nil), b.subs[e.Type]...)
	b.mu.RUnlock()
	var errs []error
	for _, s := range subs {
		if err := s.handle(ctx, e); err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", s.consumer, err))
		}
	}
	return errors.Join(errs...)
}
