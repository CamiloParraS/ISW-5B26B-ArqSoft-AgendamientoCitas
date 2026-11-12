// Package clinical is the Clinical module's public interface (report: Atención clínica).
// Only this module's role (clinical_app) can read the clinical schema.
package clinical

import (
	"context"
	"net/http"

	"clinic/audit"
	"clinic/clinical/internal/application"
	"clinic/clinical/internal/domain"
	"clinic/clinical/internal/infrastructure"
	"clinic/clinical/internal/presentation"
	"clinic/internal/platform/clock"
	"clinic/internal/platform/events"
	"clinic/internal/platform/httpx"

	"github.com/jackc/pgx/v5/pgxpool"
)

type (
	Encounter = application.Encounter
	Reader    = application.Reader
)

var (
	ErrForbidden        = application.ErrForbidden
	ErrAuditUnavailable = application.ErrAuditUnavailable
)

// AccessLog is the part of Audit that Clinical needs.
type AccessLog interface {
	Record(ctx context.Context, e audit.Entry) error
}

type Deps struct {
	DB    *pgxpool.Pool // connected as clinical_app
	Clock clock.Clock
	Bus   *events.Bus
	Auth  httpx.Authenticator
	Audit AccessLog
	Key   []byte // 32-byte AES key for Diagnosis and Prescription
}

type Module struct {
	svc      *application.Service
	handlers presentation.Handlers
}

func New(d Deps) (*Module, error) {
	c, err := infrastructure.NewCipher(d.Key)
	if err != nil {
		return nil, err
	}
	svc := application.NewService(infrastructure.Repo{DB: d.DB, Cipher: c}, auditAdapter{d.Audit}, d.Clock)
	svc.Subscribe(d.Bus)
	return &Module{svc: svc, handlers: presentation.Handlers{Svc: svc, Auth: d.Auth}}, nil
}

func (m *Module) Routes(mux *http.ServeMux) { m.handlers.Routes(mux) }

// ReadRecord is the audited, authorized read of a Patient's Encounters.
func (m *Module) ReadRecord(ctx context.Context, r Reader, patientID string) ([]Encounter, error) {
	return m.svc.ReadRecord(ctx, r, patientID)
}

// Seal encrypts a value the way the encounters table stores it (used by the seeder).
func Seal(key []byte, plain string) ([]byte, error) {
	c, err := infrastructure.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return c.Seal(plain), nil
}

type auditAdapter struct{ log AccessLog }

func (a auditAdapter) Record(ctx context.Context, e domain.AccessEntry) error {
	return a.log.Record(ctx, audit.Entry{
		At: e.At, UserID: e.Reader.UserID, UserName: e.Reader.UserName, Role: e.Reader.Role,
		Action: e.Action, Resource: e.Resource, PatientID: e.PatientID, Allowed: e.Allowed,
		Reason: e.Reason, Source: e.Reader.Source,
	})
}
