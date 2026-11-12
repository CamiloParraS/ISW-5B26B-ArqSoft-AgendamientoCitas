// Command fakeprovider imitates the external Messaging provider. It can be
// switched off (answers 503) to show graceful degradation (T12) and retries (T13).
package main

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"
)

type received struct {
	MessageID  string    `json:"message_id"`
	Channel    string    `json:"channel"`
	To         string    `json:"to"`
	Kind       string    `json:"kind"`
	Body       string    `json:"body"`
	ReceivedAt time.Time `json:"received_at"`
}

type provider struct {
	mu       sync.Mutex
	up       bool
	rejected int
	inbox    []received
	seen     map[string]bool
}

func main() {
	p := &provider{up: true, seen: map[string]bool{}}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /messages", p.send)
	mux.HandleFunc("GET /messages", p.list)
	mux.HandleFunc("GET /state", p.state)
	mux.HandleFunc("POST /state", p.state)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	addr := ":" + envOr("PORT", "8081")
	slog.Info("fake messaging provider listening", "addr", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		slog.Error("server", "err", err)
		os.Exit(1)
	}
}

func (p *provider) send(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.up {
		p.rejected++
		http.Error(w, "provider unavailable (switched off for the demo)", http.StatusServiceUnavailable)
		return
	}
	var m received
	if err := json.NewDecoder(r.Body).Decode(&m); err != nil {
		http.Error(w, "bad message", http.StatusBadRequest)
		return
	}
	// A real provider deduplicates on the idempotency key; so do we.
	if key := r.Header.Get("Idempotency-Key"); key != "" && p.seen[key] {
		w.WriteHeader(http.StatusOK)
		return
	}
	m.ReceivedAt = time.Now().UTC()
	p.seen[r.Header.Get("Idempotency-Key")] = true
	p.inbox = append(p.inbox, m)
	slog.Info("message received", "to", m.To, "kind", m.Kind)
	w.WriteHeader(http.StatusAccepted)
}

func (p *provider) list(w http.ResponseWriter, _ *http.Request) {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]received, len(p.inbox))
	for i := range p.inbox { // newest first
		out[i] = p.inbox[len(p.inbox)-1-i]
	}
	writeJSON(w, out)
}

func (p *provider) state(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if r.Method == http.MethodPost {
		var in struct {
			Up *bool `json:"up"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err == nil && in.Up != nil {
			p.up = *in.Up
			slog.Info("provider switched", "up", p.up)
		}
	}
	writeJSON(w, map[string]any{"up": p.up, "received": len(p.inbox), "rejected": p.rejected})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
