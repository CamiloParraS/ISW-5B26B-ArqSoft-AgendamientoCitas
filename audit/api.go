// Package audit is the Audit module's public interface: the append-only Access log.
package audit

import (
	"context"
	"net/http"

	"clinic/audit/internal/application"
	"clinic/audit/internal/infrastructure"
	"clinic/audit/internal/presentation"
	"clinic/internal/platform/clock"
	"clinic/internal/platform/httpx"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Entry is one Access entry.
type Entry = application.Entry

type Deps struct {
	DB    *pgxpool.Pool // connected as audit_app
	Clock clock.Clock
	Auth  httpx.Authenticator
}

type Module struct {
	svc      *application.Service
	handlers presentation.Handlers
}

func New(d Deps) *Module {
	svc := application.NewService(infrastructure.Log{DB: d.DB}, d.Clock)
	return &Module{svc: svc, handlers: presentation.Handlers{Svc: svc, Auth: d.Auth}}
}

func (m *Module) Routes(mux *http.ServeMux) { m.handlers.Routes(mux) }

// Record appends an Access entry. An error means the caller must not proceed (fail closed).
func (m *Module) Record(ctx context.Context, e Entry) error { return m.svc.Record(ctx, e) }

func (m *Module) Recent(ctx context.Context, limit int) ([]Entry, error) { return m.svc.Recent(ctx, limit) }
