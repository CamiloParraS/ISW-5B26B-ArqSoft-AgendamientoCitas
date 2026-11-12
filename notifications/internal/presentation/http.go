// Package presentation exposes Notifications over HTTP.
package presentation

import (
	"net/http"

	"clinic/internal/platform/httpx"
	"clinic/notifications/internal/application"
)

type Handlers struct {
	Svc  *application.Service
	Auth httpx.Authenticator
}

func (h Handlers) Routes(mux *http.ServeMux) {
	mux.Handle("GET /api/notifications/messages",
		h.Auth.Require(httpx.RolePatient, httpx.RoleStaff)(http.HandlerFunc(h.list)))
}

// list: a Patient sees their own Messages; Staff sees all.
func (h Handlers) list(w http.ResponseWriter, r *http.Request) {
	p, _ := httpx.PrincipalFrom(r.Context())
	patientID := ""
	if p.Role == httpx.RolePatient {
		patientID = p.SubjectID
	}
	ms, err := h.Svc.List(r.Context(), patientID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "could not list messages")
		return
	}
	httpx.JSON(w, http.StatusOK, ms)
}
