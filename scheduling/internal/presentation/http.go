// Package presentation exposes Scheduling over HTTP. It depends only on the
// application layer (and the platform kernel), never on domain or infrastructure.
package presentation

import (
	"errors"
	"net/http"
	"time"

	"clinic/internal/platform/httpx"
	"clinic/scheduling/internal/application"
)

type Handlers struct {
	Svc  *application.Service
	Auth httpx.Authenticator
}

func (h Handlers) Routes(mux *http.ServeMux) {
	any := h.Auth.Require()
	mux.Handle("GET /api/scheduling/doctors", any(http.HandlerFunc(h.doctors)))
	mux.Handle("GET /api/scheduling/slots", any(http.HandlerFunc(h.slots)))
	mux.Handle("POST /api/scheduling/slots", h.Auth.Require(httpx.RoleStaff)(http.HandlerFunc(h.openSlot)))
	mux.Handle("GET /api/scheduling/appointments", h.Auth.Require(httpx.RolePatient, httpx.RoleDoctor, httpx.RoleStaff)(http.HandlerFunc(h.list)))
	mux.Handle("POST /api/scheduling/appointments", h.Auth.Require(httpx.RolePatient)(http.HandlerFunc(h.book)))
	mux.Handle("POST /api/scheduling/appointments/{id}/cancel", h.Auth.Require(httpx.RolePatient, httpx.RoleStaff)(http.HandlerFunc(h.cancel)))
	mux.Handle("POST /api/scheduling/appointments/{id}/reschedule", h.Auth.Require(httpx.RolePatient, httpx.RoleStaff)(http.HandlerFunc(h.reschedule)))
	mux.Handle("POST /api/scheduling/appointments/{id}/check-in", h.Auth.Require(httpx.RoleDoctor)(http.HandlerFunc(h.checkIn)))
}

func actor(r *http.Request) application.Actor {
	p, _ := httpx.PrincipalFrom(r.Context())
	return application.Actor{Role: p.Role, SubjectID: p.SubjectID}
}

func (h Handlers) doctors(w http.ResponseWriter, r *http.Request) {
	ds, err := h.Svc.Doctors(r.Context())
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, ds)
}

func (h Handlers) slots(w http.ResponseWriter, r *http.Request) {
	f := application.SlotFilter{DoctorID: r.URL.Query().Get("doctor_id")}
	if from := r.URL.Query().Get("from"); from != "" {
		if t, err := time.Parse(time.RFC3339, from); err == nil {
			f.From = t
		}
	}
	ss, err := h.Svc.OpenSlots(r.Context(), f)
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, ss)
}

func (h Handlers) openSlot(w http.ResponseWriter, r *http.Request) {
	var in struct {
		DoctorID string    `json:"doctor_id"`
		StartsAt time.Time `json:"starts_at"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {doctor_id, starts_at (RFC3339)}")
		return
	}
	s, err := h.Svc.OpenSlot(r.Context(), in.DoctorID, in.StartsAt)
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, s)
}

func (h Handlers) list(w http.ResponseWriter, r *http.Request) {
	as, err := h.Svc.List(r.Context(), actor(r))
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, as)
}

func (h Handlers) book(w http.ResponseWriter, r *http.Request) {
	var in struct {
		SlotID string `json:"slot_id"`
	}
	if err := httpx.Decode(r, &in); err != nil || in.SlotID == "" {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {slot_id}")
		return
	}
	res, err := h.Svc.Book(r.Context(), actor(r).SubjectID, in.SlotID, r.Header.Get("Idempotency-Key"))
	if errors.Is(err, application.ErrSlotTaken) {
		httpx.JSON(w, http.StatusConflict, map[string]any{
			"error": "slot_taken", "message": "That slot was just taken.", "alternatives": res.Alternatives,
		})
		return
	}
	if err != nil {
		fail(w, err)
		return
	}
	status := http.StatusCreated
	if !res.Created {
		status = http.StatusOK // idempotent replay
	}
	httpx.JSON(w, status, res.Appointment)
}

func (h Handlers) cancel(w http.ResponseWriter, r *http.Request) {
	a, err := h.Svc.Cancel(r.Context(), actor(r), r.PathValue("id"))
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, a)
}

func (h Handlers) reschedule(w http.ResponseWriter, r *http.Request) {
	var in struct {
		SlotID string `json:"slot_id"`
	}
	if err := httpx.Decode(r, &in); err != nil || in.SlotID == "" {
		httpx.Error(w, http.StatusBadRequest, "bad_request", "body must be {slot_id}")
		return
	}
	a, err := h.Svc.Reschedule(r.Context(), actor(r), r.PathValue("id"), in.SlotID)
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, a)
}

func (h Handlers) checkIn(w http.ResponseWriter, r *http.Request) {
	a, err := h.Svc.CheckIn(r.Context(), actor(r), r.PathValue("id"))
	if err != nil {
		fail(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, a)
}

func fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, application.ErrNotFound), errors.Is(err, application.ErrNotYours):
		httpx.Error(w, http.StatusNotFound, "not_found", "appointment not found")
	case errors.Is(err, application.ErrSlotNotFound), errors.Is(err, application.ErrDoctorNotFound):
		httpx.Error(w, http.StatusNotFound, "not_found", err.Error())
	case errors.Is(err, application.ErrSlotTaken):
		httpx.Error(w, http.StatusConflict, "slot_taken", err.Error())
	case errors.Is(err, application.ErrSlotExists):
		httpx.Error(w, http.StatusConflict, "slot_exists", err.Error())
	case errors.Is(err, application.ErrTooLate):
		httpx.Error(w, http.StatusUnprocessableEntity, "too_late", err.Error())
	case errors.Is(err, application.ErrNotConfirmed):
		httpx.Error(w, http.StatusConflict, "not_confirmed", err.Error())
	case errors.Is(err, application.ErrSlotInPast):
		httpx.Error(w, http.StatusUnprocessableEntity, "slot_in_past", err.Error())
	case errors.Is(err, application.ErrKeyRequired):
		httpx.Error(w, http.StatusBadRequest, "idempotency_key_required", err.Error())
	default:
		httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong; you can retry")
	}
}
