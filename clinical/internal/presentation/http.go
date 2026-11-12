// Package presentation exposes Clinical records over HTTP.
package presentation

import (
	"errors"
	"net/http"

	"clinic/clinical/internal/application"
	"clinic/internal/platform/httpx"
)

type Handlers struct {
	Svc  *application.Service
	Auth httpx.Authenticator
}

func (h Handlers) Routes(mux *http.ServeMux) {
	// Every role may *ask*; the use case decides and audits (so a Staff attempt is logged too).
	any := h.Auth.Require()
	mux.Handle("GET /api/clinical/patients/{id}/encounters", any(http.HandlerFunc(h.read)))
	mux.Handle("POST /api/clinical/patients/{id}/encounters", any(http.HandlerFunc(h.write)))
}

func reader(r *http.Request) application.Reader {
	p, _ := httpx.PrincipalFrom(r.Context())
	return application.Reader{UserID: p.UserID, UserName: p.Name, Role: p.Role, SubjectID: p.SubjectID, Source: httpx.Source(r)}
}

func (h Handlers) read(w http.ResponseWriter, r *http.Request) {
	es, err := h.Svc.ReadRecord(r.Context(), reader(r), r.PathValue("id"))
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, es)
}

func (h Handlers) write(w http.ResponseWriter, r *http.Request) {
	var in struct{ Diagnosis, Prescription string }
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {diagnosis, prescription}")
		return
	}
	e, err := h.Svc.RecordEncounter(r.Context(), reader(r), r.PathValue("id"), in.Diagnosis, in.Prescription)
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, e)
}

func fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, application.ErrForbidden):
		// Same answer whether or not the Patient has records (E-2).
		httpx.Error(w, http.StatusForbidden, "forbidden", "You are not allowed to see this clinical record.")
	case errors.Is(err, application.ErrAuditUnavailable):
		httpx.Error(w, http.StatusServiceUnavailable, "audit_unavailable", err.Error())
	case errors.Is(err, application.ErrInvalid):
		httpx.Error(w, http.StatusBadRequest, "bad_request", err.Error())
	default:
		// Never echo the error: it could carry clinical data.
		httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
	}
}
