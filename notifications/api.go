// Package notifications is the Notifications module's public interface.
// It consumes Scheduling's events and talks to the Messaging provider.
package notifications

import (
	"context"
	"net/http"
	"time"

	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/internal/platform/httpx"
	"clinic/notifications/internal/application"
	"clinic/notifications/internal/infrastructure"
	"clinic/notifications/internal/presentation"

	"github.com/jackc/pgx/v5/pgxpool"
)

type (
	Message        = application.Message
	DeliveryReport = application.DeliveryReport
)

type Deps struct {
	DB          *pgxpool.Pool // connected as notifications_app
	Clock       clock.Clock
	Bus         *events.Bus
	Auth        httpx.Authenticator
	ProviderURL string
}

type Module struct {
	svc      *application.Service
	handlers presentation.Handlers
}

func New(d Deps) *Module {
	provider := infrastructure.HTTPProvider{BaseURL: d.ProviderURL, Client: &http.Client{Timeout: 5 * time.Second}}
	svc := application.NewService(infrastructure.Repo{DB: d.DB}, provider, d.Clock)
	svc.Subscribe(d.Bus)
	return &Module{svc: svc, handlers: presentation.Handlers{Svc: svc, Auth: d.Auth}}
}

func (m *Module) Routes(mux *http.ServeMux) { m.handlers.Routes(mux) }

// DeliverDue sends due Messages to the provider, with retries.
func (m *Module) DeliverDue(ctx context.Context) (DeliveryReport, error) { return m.svc.DeliverDue(ctx) }

// Messages lists Messages, all of them when patientID is empty.
func (m *Module) Messages(ctx context.Context, patientID string) ([]Message, error) {
	return m.svc.List(ctx, patientID)
}
