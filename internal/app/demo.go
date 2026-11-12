package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"clinic/internal/platform/clock"
	"clinic/internal/platform/httpx"
	"clinic/internal/platform/ids"
	"clinic/scheduling"
)

// Demo-only endpoints, registered only when DEMO_MODE=true. They exist because a
// browser cannot move time, switch a container off, open 50 parallel connections
// or kill a process (the report's live-demo script needs all four).
func (a *App) demoRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /demo/clock", a.demoClock)
	mux.HandleFunc("POST /demo/clock", a.demoClock)
	mux.HandleFunc("POST /demo/tick", a.demoTick)
	mux.HandleFunc("GET /demo/provider", a.demoProvider)
	mux.HandleFunc("POST /demo/provider", a.demoProvider)
	mux.HandleFunc("POST /demo/burst", a.demoBurst)
	mux.HandleFunc("POST /demo/crash", a.demoCrash)
	mux.HandleFunc("GET /demo/messages", a.demoMessages)
	mux.HandleFunc("GET /demo/appointments", a.demoAppointments)
}

func (a *App) demoClock(w http.ResponseWriter, r *http.Request) {
	off, ok := a.Clock.(*clock.Offset)
	if !ok {
		httpx.Error(w, http.StatusConflict, "fixed_clock", "this clock cannot be moved")
		return
	}
	if r.Method == http.MethodPost {
		var in struct {
			Advance string `json:"advance"`
			Reset   bool   `json:"reset"`
		}
		if err := httpx.Decode(r, &in); err != nil {
			httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {advance: \"16m\"} or {reset: true}")
			return
		}
		if in.Reset {
			off.Reset()
		} else if d, err := time.ParseDuration(in.Advance); err == nil && d > 0 && d <= 30*24*time.Hour {
			off.Advance(d)
		} else {
			httpx.Error(w, http.StatusBadRequest, "bad_request", "advance must be a positive duration like 16m or 24h")
			return
		}
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"now": off.Now(), "offset_seconds": int64(off.Offset().Seconds())})
}

func (a *App) demoTick(w http.ResponseWriter, r *http.Request) {
	rep, err := a.Tick(r.Context())
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "tick_failed", err.Error())
		return
	}
	httpx.JSON(w, http.StatusOK, rep)
}

// demoProvider proxies the fake provider so the page stays on one origin.
func (a *App) demoProvider(w http.ResponseWriter, r *http.Request) {
	client := &http.Client{Timeout: 3 * time.Second}
	if r.Method == http.MethodPost {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<10))
		resp, err := client.Post(a.cfg.ProviderURL+"/state", "application/json", strings.NewReader(string(body)))
		if err != nil {
			httpx.Error(w, http.StatusBadGateway, "provider_unreachable", err.Error())
			return
		}
		resp.Body.Close()
	}
	out := map[string]any{"reachable": false}
	if resp, err := client.Get(a.cfg.ProviderURL + "/state"); err == nil {
		var st map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&st)
		resp.Body.Close()
		out["reachable"], out["state"] = true, st
	}
	if resp, err := client.Get(a.cfg.ProviderURL + "/messages"); err == nil {
		var ms []map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&ms)
		resp.Body.Close()
		out["received"] = ms
	}
	httpx.JSON(w, http.StatusOK, out)
}

func (a *App) demoMessages(w http.ResponseWriter, r *http.Request) {
	ms, err := a.Notifications.Messages(r.Context(), "")
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}
	httpx.JSON(w, http.StatusOK, ms)
}

func (a *App) demoAppointments(w http.ResponseWriter, r *http.Request) {
	as, err := a.Scheduling.Appointments(r.Context())
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}
	httpx.JSON(w, http.StatusOK, as)
}

type burstResult struct {
	Patient       string `json:"patient"`
	Outcome       string `json:"outcome"` // confirmed | slot_taken | error
	AppointmentID string `json:"appointment_id,omitempty"`
	MS            float64 `json:"ms"`
}

// demoBurst is E-1 live: N goroutines, released at the same instant, each a
// different Patient, all booking the same Slot through Scheduling's public API.
func (a *App) demoBurst(w http.ResponseWriter, r *http.Request) {
	var in struct {
		SlotID string `json:"slot_id"`
		N      int    `json:"n"`
	}
	if err := httpx.Decode(r, &in); err != nil || in.SlotID == "" {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {slot_id, n}")
		return
	}
	if in.N <= 0 || in.N > 50 {
		in.N = 50
	}
	patients, err := a.Scheduling.Patients(r.Context())
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}
	patients = slices.DeleteFunc(patients, func(p scheduling.Patient) bool { return !strings.HasPrefix(p.Name, "Burst patient") })
	if len(patients) < in.N {
		httpx.Error(w, http.StatusConflict, "not_seeded", "burst patients are missing; run migrate -seed")
		return
	}
	results := make([]burstResult, in.N)
	var alternatives []scheduling.Slot
	var altOnce sync.Once
	var wg sync.WaitGroup
	gate := make(chan struct{})
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	for i := range in.N {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p := patients[i]
			<-gate
			start := time.Now()
			res, err := a.Scheduling.Book(ctx, p.ID, in.SlotID, ids.New())
			out := burstResult{Patient: p.Name, MS: float64(time.Since(start).Microseconds()) / 1000}
			switch {
			case err == nil:
				out.Outcome, out.AppointmentID = "confirmed", res.Appointment.ID
			case errors.Is(err, scheduling.ErrSlotTaken):
				out.Outcome = "slot_taken"
				altOnce.Do(func() { alternatives = res.Alternatives })
			default:
				out.Outcome = "error"
				slog.Warn("burst", "err", err)
			}
			results[i] = out
		}()
	}
	start := time.Now()
	close(gate)
	wg.Wait()
	lat := make([]float64, 0, len(results))
	counts := map[string]int{}
	for _, r := range results {
		lat = append(lat, r.MS)
		counts[r.Outcome]++
	}
	slices.Sort(lat)
	p95 := lat[int(float64(len(lat)-1)*0.95)]
	httpx.JSON(w, http.StatusOK, map[string]any{
		"results": results, "counts": counts, "p95_ms": p95, "max_ms": lat[len(lat)-1],
		"wall_ms": time.Since(start).Milliseconds(), "alternatives": alternatives,
	})
}

// demoCrash stands in for E-3's stimulus (an unhandled error): the process exits
// with status 1 and the platform (compose restart policy) brings it back.
func (a *App) demoCrash(w http.ResponseWriter, r *http.Request) {
	httpx.JSON(w, http.StatusAccepted, map[string]string{"status": "crashing"})
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	go func() {
		time.Sleep(300 * time.Millisecond)
		slog.Error("demo crash requested: exiting with status 1")
		os.Exit(1)
	}()
}
