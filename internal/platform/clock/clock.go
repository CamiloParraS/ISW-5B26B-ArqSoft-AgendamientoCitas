// Package clock is the injectable clock (report section 7, testability tactic X-1).
// The domain never reads the system time; it receives a Clock.
package clock

import (
	"sync"
	"time"
)

// Clock tells the current time.
type Clock interface {
	Now() time.Time
}

// System is the real wall clock, in UTC.
type System struct{}

func (System) Now() time.Time { return time.Now().UTC() }

// Offset runs at real speed shifted by an adjustable offset. It is used only
// when DEMO_MODE is on, so the demo can move time forward (24 h rule, 15 min rule).
type Offset struct {
	mu     sync.RWMutex
	offset time.Duration
}

func NewOffset() *Offset { return &Offset{} }

func (o *Offset) Now() time.Time {
	o.mu.RLock()
	defer o.mu.RUnlock()
	return time.Now().UTC().Add(o.offset)
}

func (o *Offset) Advance(d time.Duration) {
	o.mu.Lock()
	o.offset += d
	o.mu.Unlock()
}

func (o *Offset) Reset() {
	o.mu.Lock()
	o.offset = 0
	o.mu.Unlock()
}

func (o *Offset) Offset() time.Duration {
	o.mu.RLock()
	defer o.mu.RUnlock()
	return o.offset
}

// Fixed is a clock for tests: it only moves when told to.
type Fixed struct {
	mu sync.Mutex
	t  time.Time
}

func NewFixed(t time.Time) *Fixed { return &Fixed{t: t.UTC()} }

func (f *Fixed) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.t
}

func (f *Fixed) Set(t time.Time) {
	f.mu.Lock()
	f.t = t.UTC()
	f.mu.Unlock()
}

func (f *Fixed) Advance(d time.Duration) {
	f.mu.Lock()
	f.t = f.t.Add(d)
	f.mu.Unlock()
}
