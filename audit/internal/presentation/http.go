// Package presentation exposes the Access log to the Data Protection Officer.
package presentation

import (
	"net/http"
	"strconv"

	"clinic/audit/internal/application"
	"clinic/internal/platform/httpx"
)

type Handlers struct {
	Svc  *application.Service
	Auth httpx.Authenticator
}

func (h Handlers) Routes(mux *http.ServeMux) {
	mux.Handle("GET /api/audit/access-entries", h.Auth.Require(httpx.RoleDPO)(http.HandlerFunc(h.list)))
}

func (h Handlers) list(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	es, err := h.Svc.Recent(r.Context(), limit)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "could not read the access log")
		return
	}
	httpx.JSON(w, http.StatusOK, es)
}
