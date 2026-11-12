// Package httpx holds HTTP helpers shared by every module's presentation layer:
// JSON encoding, the authenticated Principal, and the Authenticator contract
// that Identity implements. It knows no module.
package httpx

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
)

// Principal is who made the request, as proven by an Access token.
type Principal struct {
	UserID    string `json:"user_id"`
	Role      string `json:"role"`
	SubjectID string `json:"subject_id"` // patient or doctor id, empty for staff and dpo
	Name      string `json:"name"`
}

// Role ids (see GLOSSARY.md).
const (
	RolePatient = "patient"
	RoleDoctor  = "doctor"
	RoleStaff   = "staff"
	RoleDPO     = "dpo"
)

// Authenticator wraps handlers so only valid tokens with one of the given roles pass.
// Identity implements it; other modules receive it and never issue tokens.
type Authenticator interface {
	Require(roles ...string) func(http.Handler) http.Handler
}

type principalKey struct{}

func WithPrincipal(ctx context.Context, p Principal) context.Context {
	return context.WithValue(ctx, principalKey{}, p)
}

func PrincipalFrom(ctx context.Context) (Principal, bool) {
	p, ok := ctx.Value(principalKey{}).(Principal)
	return p, ok
}

// JSON writes v with the given status.
func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Error writes {"error": code, "message": msg}.
func Error(w http.ResponseWriter, status int, code, msg string) {
	JSON(w, status, map[string]string{"error": code, "message": msg})
}

// Decode reads a JSON body into v.
func Decode(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
	return dec.Decode(v)
}

// Source returns where a request came from, for the Access log.
func Source(r *http.Request) string {
	if f := r.Header.Get("X-Forwarded-For"); f != "" {
		return strings.TrimSpace(strings.Split(f, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
