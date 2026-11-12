// Package identity is the Identity and access module's public interface.
// It issues Access tokens; every other module only verifies them, through
// the httpx.Authenticator this Module implements.
package identity

import (
	"net/http"
	"time"

	"clinic/identity/internal/application"
	"clinic/identity/internal/domain"
	"clinic/identity/internal/infrastructure"
	"clinic/identity/internal/presentation"
	"clinic/internal/platform/clock"
	"clinic/internal/platform/httpx"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Deps struct {
	DB       *pgxpool.Pool // connected as identity_app
	Clock    clock.Clock
	Secret   []byte
	TokenTTL time.Duration
	Demo     bool
}

type Module struct {
	svc      *application.Service
	handlers presentation.Handlers
}

var _ httpx.Authenticator = (*Module)(nil)

func New(d Deps) *Module {
	if d.TokenTTL == 0 {
		d.TokenTTL = 8 * time.Hour
	}
	svc := application.NewService(infrastructure.Users{DB: d.DB}, d.Secret, d.Clock, d.TokenTTL)
	return &Module{svc: svc, handlers: presentation.Handlers{Svc: svc, Demo: d.Demo}}
}

func (m *Module) Routes(mux *http.ServeMux) { m.handlers.Routes(mux) }

// Require implements httpx.Authenticator.
func (m *Module) Require(roles ...string) func(http.Handler) http.Handler {
	return m.handlers.Require(roles...)
}

// HashPassword is exported for seeding users.
func HashPassword(pw string) (string, error) { return domain.HashPassword(pw) }
