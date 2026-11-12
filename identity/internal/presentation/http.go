// Package presentation exposes Identity over HTTP and provides the auth middleware.
package presentation

import (
	"errors"
	"net/http"
	"slices"
	"strings"

	"clinic/identity/internal/application"
	"clinic/internal/platform/httpx"
)

type Handlers struct {
	Svc  *application.Service
	Demo bool
}

func (h Handlers) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/identity/tokens", h.login)
	mux.Handle("GET /api/identity/me", h.Require()(http.HandlerFunc(h.me)))
	if h.Demo {
		mux.HandleFunc("GET /api/identity/demo-users", h.demoUsers)
	}
}

func (h Handlers) login(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Password string }
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {username, password}")
		return
	}
	tok, cl, err := h.Svc.Login(r.Context(), in.Username, in.Password)
	if errors.Is(err, application.ErrInvalidCredentials) {
		httpx.Error(w, http.StatusUnauthorized, "invalid_credentials", err.Error())
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "could not log in")
		return
	}
	httpx.JSON(w, http.StatusCreated, map[string]any{"access_token": tok, "claims": cl})
}

func (h Handlers) me(w http.ResponseWriter, r *http.Request) {
	p, _ := httpx.PrincipalFrom(r.Context())
	httpx.JSON(w, http.StatusOK, p)
}

func (h Handlers) demoUsers(w http.ResponseWriter, r *http.Request) {
	us, err := h.Svc.Users(r.Context())
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "could not list users")
		return
	}
	out := make([]map[string]string, 0, len(us))
	for _, u := range us {
		out = append(out, map[string]string{"username": u.Username, "role": u.Role, "name": u.DisplayName, "subject_id": u.SubjectID})
	}
	httpx.JSON(w, http.StatusOK, out)
}

// Require verifies the Bearer Access token and, if roles are given, the role.
func (h Handlers) Require(roles ...string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			raw, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			if !ok {
				httpx.Error(w, http.StatusUnauthorized, "unauthenticated", "missing Bearer access token")
				return
			}
			cl, err := h.Svc.Verify(raw)
			if err != nil {
				httpx.Error(w, http.StatusUnauthorized, "unauthenticated", err.Error())
				return
			}
			if len(roles) > 0 && !slices.Contains(roles, cl.Role) {
				httpx.Error(w, http.StatusForbidden, "forbidden", "your role cannot do this")
				return
			}
			p := httpx.Principal{UserID: cl.Sub, Role: cl.Role, SubjectID: cl.SubjectID, Name: cl.Name}
			next.ServeHTTP(w, r.WithContext(httpx.WithPrincipal(r.Context(), p)))
		})
	}
}
