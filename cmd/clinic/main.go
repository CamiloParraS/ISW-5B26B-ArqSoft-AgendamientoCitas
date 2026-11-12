// Command clinic is the modular monolith: one process, five modules.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"clinic/internal/app"
	"clinic/internal/platform/clock"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, nil)))
	demo := env("DEMO_MODE", "false") == "true"
	cfg, err := app.ConfigFromStrings(
		env("DATABASE_URL", "postgres://postgres@localhost:55432/clinic"),
		os.Getenv("TOKEN_SECRET"), os.Getenv("TICK_TOKEN"), os.Getenv("CLINICAL_KEY"),
		env("PROVIDER_URL", "http://localhost:8081"), demo)
	if err != nil {
		slog.Error("config", "err", err)
		os.Exit(2)
	}
	var clk clock.Clock = clock.System{}
	if demo {
		clk = clock.NewOffset()
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	a, err := app.New(ctx, cfg, clk)
	if err != nil {
		slog.Error("startup", "err", err)
		os.Exit(1)
	}
	defer a.Close()

	drainEvery, _ := time.ParseDuration(env("DRAIN_INTERVAL", "2s"))
	go a.RunDrainLoop(ctx, drainEvery)

	srv := &http.Server{Addr: ":" + env("PORT", "8080"), Handler: a.Handler(), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		sh, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sh)
	}()
	slog.Info("clinic listening", "addr", srv.Addr, "demo", demo)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("server", "err", err)
		os.Exit(1)
	}
}
