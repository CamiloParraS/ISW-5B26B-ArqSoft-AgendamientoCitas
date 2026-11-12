// Package app assembles the modular monolith: one pool per module role, the
// clock, the event bus, the five modules, the Tick, /health and the drain loop.
// cmd/clinic only reads configuration and calls this package.
package app

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"clinic/audit"
	"clinic/clinical"
	"clinic/identity"
	"clinic/internal/migrations"
	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/internal/platform/httpx"
	"clinic/notifications"
	"clinic/scheduling"
	"clinic/web"

	"github.com/jackc/pgx/v5/pgxpool"
)

// HeartbeatStaleAfter: /health turns degraded when the last Sweep is older (RS-2).
const HeartbeatStaleAfter = 15 * time.Minute

type Config struct {
	DatabaseURL string // host, port and database; user/password are replaced per module role
	TokenSecret []byte
	TickToken   string
	ClinicalKey []byte
	ProviderURL string
	Demo        bool
}

// ConfigFromStrings parses the hex clinical key and fills defaults.
func ConfigFromStrings(dbURL, tokenSecret, tickToken, clinicalKeyHex, providerURL string, demo bool) (Config, error) {
	key, err := hex.DecodeString(clinicalKeyHex)
	if err != nil || len(key) != 32 {
		return Config{}, errors.New("CLINICAL_KEY must be 64 hex characters (32 bytes)")
	}
	if len(tokenSecret) < 16 {
		return Config{}, errors.New("TOKEN_SECRET must be at least 16 characters")
	}
	if tickToken == "" {
		return Config{}, errors.New("TICK_TOKEN is required")
	}
	return Config{DatabaseURL: dbURL, TokenSecret: []byte(tokenSecret), TickToken: tickToken,
		ClinicalKey: key, ProviderURL: providerURL, Demo: demo}, nil
}

type App struct {
	cfg     Config
	Clock   clock.Clock
	Bus     *events.Bus
	pools   map[string]*pgxpool.Pool
	started time.Time

	Identity      *identity.Module
	Scheduling    *scheduling.Module
	Notifications *notifications.Module
	Clinical      *clinical.Module
	Audit         *audit.Module

	tickMu sync.Mutex
}

// New connects one pool per module role and wires the modules together.
func New(ctx context.Context, cfg Config, clk clock.Clock) (*App, error) {
	a := &App{cfg: cfg, Clock: clk, Bus: events.NewBus(), pools: map[string]*pgxpool.Pool{}, started: time.Now()}
	sizes := map[string]int32{"identity": 4, "scheduling": 16, "clinical": 4, "notifications": 4, "audit": 4}
	for _, m := range migrations.Modules {
		dsn, err := migrations.ModuleDSN(cfg.DatabaseURL, m)
		if err != nil {
			return nil, err
		}
		pc, err := pgxpool.ParseConfig(dsn)
		if err != nil {
			return nil, err
		}
		pc.MaxConns = sizes[m]
		pool, err := pgxpool.NewWithConfig(ctx, pc)
		if err != nil {
			a.Close()
			return nil, fmt.Errorf("%s pool: %w", m, err)
		}
		a.pools[m] = pool
	}
	a.Identity = identity.New(identity.Deps{DB: a.pools["identity"], Clock: clk, Secret: cfg.TokenSecret, Demo: cfg.Demo})
	auth := httpx.Authenticator(a.Identity)
	a.Audit = audit.New(audit.Deps{DB: a.pools["audit"], Clock: clk, Auth: auth})
	a.Scheduling = scheduling.New(scheduling.Deps{DB: a.pools["scheduling"], Clock: clk, Bus: a.Bus, Auth: auth})
	a.Notifications = notifications.New(notifications.Deps{DB: a.pools["notifications"], Clock: clk, Bus: a.Bus, Auth: auth, ProviderURL: cfg.ProviderURL})
	var err error
	a.Clinical, err = clinical.New(clinical.Deps{DB: a.pools["clinical"], Clock: clk, Bus: a.Bus, Auth: auth, Audit: a.Audit, Key: cfg.ClinicalKey})
	if err != nil {
		a.Close()
		return nil, err
	}
	return a, nil
}

func (a *App) Close() {
	for _, p := range a.pools {
		p.Close()
	}
}

// Handler is the whole HTTP surface.
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	a.Identity.Routes(mux)
	a.Scheduling.Routes(mux)
	a.Notifications.Routes(mux)
	a.Clinical.Routes(mux)
	a.Audit.Routes(mux)
	mux.HandleFunc("GET /health", a.handleHealth)
	mux.HandleFunc("POST /internal/tick", a.handleTick)
	if a.cfg.Demo {
		a.demoRoutes(mux)
	}
	mux.Handle("GET /", web.Handler())
	return logRequests(mux)
}

// ------------------------------------------------------------------- tick

type TickReport struct {
	At         time.Time                   `json:"at"`
	NoShows    int                         `json:"no_shows"`
	Delivered  int                         `json:"events_delivered"`
	EventFails int                         `json:"event_failures"`
	Messages   notifications.DeliveryReport `json:"messages"`
	TookMS     int64                       `json:"took_ms"`
}

// Tick is what the Ticker triggers (ADR-003): Sweep, drain the outbox, deliver
// due Messages. Every step is idempotent, so two Ticks in a row duplicate nothing.
func (a *App) Tick(ctx context.Context) (TickReport, error) {
	a.tickMu.Lock()
	defer a.tickMu.Unlock()
	start := time.Now()
	rep := TickReport{At: a.Clock.Now()}
	var err error
	if rep.NoShows, err = a.Scheduling.Sweep(ctx); err != nil {
		return rep, fmt.Errorf("sweep: %w", err)
	}
	d, f, err := a.drain(ctx)
	rep.Delivered, rep.EventFails, rep.Messages = d, f.events, f.messages
	rep.TookMS = time.Since(start).Milliseconds()
	return rep, err
}

type drainFailures struct {
	events   int
	messages notifications.DeliveryReport
}

func (a *App) drain(ctx context.Context) (int, drainFailures, error) {
	var df drainFailures
	delivered, failed, err := a.Scheduling.DrainOutbox(ctx)
	df.events = failed
	if err != nil {
		return delivered, df, fmt.Errorf("outbox: %w", err)
	}
	df.messages, err = a.Notifications.DeliverDue(ctx)
	if err != nil {
		return delivered, df, fmt.Errorf("deliver: %w", err)
	}
	return delivered, df, nil
}

// RunDrainLoop is amendment 1: while the process is awake, relay the outbox and
// deliver Messages every interval, so effects land in seconds, not at the next Tick.
func (a *App) RunDrainLoop(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if _, _, err := a.drain(ctx); err != nil && ctx.Err() == nil {
				slog.Warn("drain loop", "err", err)
			}
		}
	}
}

func (a *App) handleTick(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Tick-Token") != a.cfg.TickToken {
		httpx.Error(w, http.StatusUnauthorized, "unauthenticated", "missing or wrong X-Tick-Token")
		return
	}
	rep, err := a.Tick(r.Context())
	if err != nil {
		slog.Error("tick", "err", err)
		httpx.Error(w, http.StatusInternalServerError, "tick_failed", "the sweep failed; the next tick retries")
		return
	}
	httpx.JSON(w, http.StatusOK, rep)
}

// ----------------------------------------------------------------- health

type Health struct {
	Status    string            `json:"status"` // ok | degraded | down
	Database  map[string]string `json:"database"`
	Heartbeat struct {
		LastSweepAt  *time.Time `json:"last_sweep_at"`
		AgeSeconds   *int64     `json:"age_seconds"`
		StaleAfter   int64      `json:"stale_after_seconds"`
		Stale        bool       `json:"stale"`
	} `json:"heartbeat"`
	OutboxPending int       `json:"outbox_pending"`
	ClockNow      time.Time `json:"clock_now"`
	StartedAt     time.Time `json:"started_at"`
	UptimeSeconds int64     `json:"uptime_seconds"`
}

func (a *App) Health(ctx context.Context) Health {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	h := Health{Status: "ok", Database: map[string]string{}, ClockNow: a.Clock.Now(), StartedAt: a.started,
		UptimeSeconds: int64(time.Since(a.started).Seconds())}
	h.Heartbeat.StaleAfter = int64(HeartbeatStaleAfter.Seconds())
	for name, p := range a.pools {
		if err := p.Ping(ctx); err != nil {
			h.Database[name+"_app"] = "unreachable"
			h.Status = "down"
		} else {
			h.Database[name+"_app"] = "ok"
		}
	}
	if h.Status == "down" {
		return h
	}
	at, ok, err := a.Scheduling.LastSweep(ctx)
	switch {
	case err != nil:
		h.Status = "down"
	case !ok:
		h.Heartbeat.Stale = true
	default:
		age := max(int64(a.Clock.Now().Sub(at).Seconds()), 0)
		h.Heartbeat.LastSweepAt, h.Heartbeat.AgeSeconds = &at, &age
		h.Heartbeat.Stale = time.Duration(age)*time.Second > HeartbeatStaleAfter
	}
	if h.Heartbeat.Stale && h.Status == "ok" {
		h.Status = "degraded"
	}
	h.OutboxPending, _ = a.Scheduling.PendingOutbox(ctx)
	return h
}

func (a *App) handleHealth(w http.ResponseWriter, r *http.Request) {
	h := a.Health(r.Context())
	status := http.StatusOK // degraded is still alive: restarting would not fix a silent Ticker
	if h.Status == "down" {
		status = http.StatusServiceUnavailable
	}
	httpx.JSON(w, status, h)
}

// ---------------------------------------------------------------- logging

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) { s.status = code; s.ResponseWriter.WriteHeader(code) }

// logRequests logs method, path and status, never bodies (no clinical data in logs, E-2).
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		start := time.Now()
		next.ServeHTTP(rec, r)
		if r.URL.Path == "/health" || r.Method == http.MethodGet && rec.status < 400 {
			return
		}
		slog.Info("http", "method", r.Method, "path", r.URL.Path, "status", rec.status, "ms", time.Since(start).Milliseconds())
	})
}
