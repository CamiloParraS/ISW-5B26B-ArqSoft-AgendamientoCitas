// Command ticker imitates the external task scheduler (the Ticker): it calls
// POST /internal/tick every TICK_INTERVAL, optionally only between TICK_HOURS
// (e.g. "7-19", Bogota time), which is also the keep-awake ping of T11.
package main

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	url := envOr("APP_URL", "http://localhost:8080") + "/internal/tick"
	token := os.Getenv("TICK_TOKEN")
	every, err := time.ParseDuration(envOr("TICK_INTERVAL", "5m"))
	if err != nil {
		slog.Error("bad TICK_INTERVAL", "err", err)
		os.Exit(2)
	}
	from, to := hours(os.Getenv("TICK_HOURS"))
	loc, err := time.LoadLocation("America/Bogota")
	if err != nil {
		loc = time.FixedZone("COT", -5*3600)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	slog.Info("ticker started", "url", url, "every", every, "hours", fmt.Sprintf("%d-%d", from, to))
	for {
		if h := time.Now().In(loc).Hour(); h >= from && h < to {
			tick(client, url, token)
		}
		time.Sleep(every)
	}
}

func tick(c *http.Client, url, token string) {
	req, _ := http.NewRequest(http.MethodPost, url, nil)
	req.Header.Set("X-Tick-Token", token)
	resp, err := c.Do(req)
	if err != nil {
		slog.Warn("tick failed (app asleep or down?)", "err", err)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 400))
	slog.Info("tick", "status", resp.StatusCode, "result", strings.TrimSpace(string(body)))
}

func hours(s string) (int, int) {
	var a, b int
	if _, err := fmt.Sscanf(s, "%d-%d", &a, &b); err != nil || a < 0 || b > 24 || a >= b {
		return 0, 24
	}
	return a, b
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
