// Package infrastructure implements Scheduling's Repository on PostgreSQL with pgx.
// The SQL is written as-is so the conditional write (T1) stays visible.
package infrastructure

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"time"

	"clinic/internal/platform/ids"
	"clinic/scheduling/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Repo struct{ DB *pgxpool.Pool }

var _ domain.Repository = Repo{}

type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

func pgCode(err error) string {
	var pe *pgconn.PgError
	if errors.As(err, &pe) {
		return pe.Code
	}
	return ""
}

// ------------------------------------------------------------------ reads

func (r Repo) Doctors(ctx context.Context) ([]domain.Doctor, error) {
	rows, err := r.DB.Query(ctx, `SELECT id::text, name, specialty FROM scheduling.doctors ORDER BY name`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Doctor, error) {
		var d domain.Doctor
		return d, row.Scan(&d.ID, &d.Name, &d.Specialty)
	})
}

func (r Repo) Patients(ctx context.Context) ([]domain.Patient, error) {
	rows, err := r.DB.Query(ctx, `SELECT id::text, name FROM scheduling.patients ORDER BY document`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Patient, error) {
		var p domain.Patient
		return p, row.Scan(&p.ID, &p.Name)
	})
}

const slotSelect = `SELECT s.id::text, s.doctor_id::text, d.name, d.specialty, s.starts_at, s.status
	FROM scheduling.slots s JOIN scheduling.doctors d ON d.id = s.doctor_id`

func scanSlot(row pgx.Row) (domain.Slot, error) {
	var s domain.Slot
	err := row.Scan(&s.ID, &s.DoctorID, &s.Doctor, &s.Specialty, &s.StartsAt, &s.Status)
	return s, err
}

func (r Repo) OpenSlots(ctx context.Context, f domain.SlotFilter) ([]domain.Slot, error) {
	rows, err := r.DB.Query(ctx, slotSelect+`
		WHERE s.status = 'open' AND s.starts_at > $1 AND ($2 = '' OR s.doctor_id::text = $2)
		ORDER BY s.starts_at, d.name LIMIT $3`, f.From, f.DoctorID, f.Limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Slot, error) { return scanSlot(row) })
}

func (r Repo) Slot(ctx context.Context, id string) (domain.Slot, error) {
	s, err := scanSlot(r.DB.QueryRow(ctx, slotSelect+` WHERE s.id::text = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return s, domain.ErrSlotNotFound
	}
	return s, err
}

func (r Repo) AddSlot(ctx context.Context, doctorID string, startsAt time.Time) (domain.Slot, error) {
	var id string
	err := r.DB.QueryRow(ctx, `INSERT INTO scheduling.slots (doctor_id, starts_at)
		SELECT id, $2 FROM scheduling.doctors WHERE id::text = $1 RETURNING id::text`, doctorID, startsAt).Scan(&id)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return domain.Slot{}, domain.ErrDoctorNotFound
	case pgCode(err) == "23505":
		return domain.Slot{}, domain.ErrSlotExists
	case err != nil:
		return domain.Slot{}, err
	}
	return r.Slot(ctx, id)
}

const apptSelect = `SELECT a.id::text, a.slot_id::text, a.patient_id::text, p.name, a.doctor_id::text, d.name, d.specialty,
	a.starts_at, a.status, a.rescheduled_from::text, a.checked_in_at, a.created_at
	FROM scheduling.appointments a
	JOIN scheduling.patients p ON p.id = a.patient_id
	JOIN scheduling.doctors d ON d.id = a.doctor_id`

func scanAppt(row pgx.Row) (domain.Appointment, error) {
	var a domain.Appointment
	err := row.Scan(&a.ID, &a.SlotID, &a.PatientID, &a.Patient, &a.DoctorID, &a.Doctor, &a.Specialty,
		&a.StartsAt, &a.Status, &a.RescheduledFrom, &a.CheckedInAt, &a.CreatedAt)
	return a, err
}

func getAppt(ctx context.Context, q querier, id string) (domain.Appointment, error) {
	a, err := scanAppt(q.QueryRow(ctx, apptSelect+` WHERE a.id::text = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return a, domain.ErrNotFound
	}
	return a, err
}

func (r Repo) Get(ctx context.Context, id string) (domain.Appointment, error) { return getAppt(ctx, r.DB, id) }

func (r Repo) list(ctx context.Context, where string, args ...any) ([]domain.Appointment, error) {
	rows, err := r.DB.Query(ctx, apptSelect+" "+where, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Appointment, error) { return scanAppt(row) })
}

func (r Repo) ListByPatient(ctx context.Context, patientID string) ([]domain.Appointment, error) {
	return r.list(ctx, `WHERE a.patient_id::text = $1 ORDER BY a.starts_at DESC`, patientID)
}

func (r Repo) ListByDoctor(ctx context.Context, doctorID string) ([]domain.Appointment, error) {
	return r.list(ctx, `WHERE a.doctor_id::text = $1 ORDER BY a.starts_at`, doctorID)
}

func (r Repo) ListAll(ctx context.Context, limit int) ([]domain.Appointment, error) {
	return r.list(ctx, `ORDER BY a.created_at DESC LIMIT $1`, limit)
}

// ------------------------------------------------------------------ writes

func insertEvent(ctx context.Context, tx pgx.Tx, eventType string, payload any, now time.Time) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO scheduling.outbox (event_id, type, payload, occurred_at, next_attempt_at)
		VALUES ($1, $2, $3::jsonb, $4, $4)`, ids.New(), eventType, string(raw), now)
	return err
}

// claimSlot is T1: the conditional write. Zero rows changed means someone else has it.
func claimSlot(ctx context.Context, tx pgx.Tx, slotID string, now time.Time) (doctorID string, startsAt time.Time, err error) {
	err = tx.QueryRow(ctx, `UPDATE scheduling.slots SET status = 'booked'
		WHERE id::text = $1 AND status = 'open' AND starts_at > $2
		RETURNING doctor_id::text, starts_at`, slotID, now).Scan(&doctorID, &startsAt)
	if !errors.Is(err, pgx.ErrNoRows) {
		return
	}
	var exists, past bool
	if err2 := tx.QueryRow(ctx, `SELECT count(*) > 0, coalesce(bool_or(starts_at <= $2), false)
		FROM scheduling.slots WHERE id::text = $1`, slotID, now).Scan(&exists, &past); err2 != nil {
		return "", time.Time{}, err2
	}
	switch {
	case !exists:
		err = domain.ErrSlotNotFound
	case past:
		err = domain.ErrSlotInPast
	default:
		err = domain.ErrSlotTaken
	}
	return
}

func insertAppointment(ctx context.Context, tx pgx.Tx, slotID, patientID, doctorID string, startsAt, now time.Time, from *string) (string, error) {
	id := ids.New()
	_, err := tx.Exec(ctx, `INSERT INTO scheduling.appointments
		(id, slot_id, patient_id, doctor_id, starts_at, status, rescheduled_from, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, 'confirmed', $6, $7, $7)`, id, slotID, patientID, doctorID, startsAt, from, now)
	if pgCode(err) == "23505" { // T2 caught a double booking
		return "", domain.ErrSlotTaken
	}
	if pgCode(err) == "23503" {
		return "", domain.ErrNotFound
	}
	return id, err
}

func (r Repo) replay(ctx context.Context, patientID, key string) (domain.Appointment, bool, error) {
	var id string
	err := r.DB.QueryRow(ctx, `SELECT appointment_id::text FROM scheduling.idempotency_keys
		WHERE patient_id::text = $1 AND key = $2`, patientID, key).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Appointment{}, false, nil
	}
	if err != nil {
		return domain.Appointment{}, false, err
	}
	a, err := r.Get(ctx, id)
	return a, err == nil, err
}

func (r Repo) Book(ctx context.Context, patientID, slotID, key string, now time.Time) (domain.Appointment, bool, error) {
	if a, ok, err := r.replay(ctx, patientID, key); ok || err != nil {
		return a, false, err
	}
	a, err := r.book(ctx, patientID, slotID, key, now)
	if errors.Is(err, domain.ErrSlotTaken) {
		// A concurrent retry with the same key may have won: return its Appointment.
		if prev, ok, err2 := r.replay(ctx, patientID, key); ok && err2 == nil {
			return prev, false, nil
		}
	}
	return a, err == nil, err
}

func (r Repo) book(ctx context.Context, patientID, slotID, key string, now time.Time) (domain.Appointment, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return domain.Appointment{}, err
	}
	defer tx.Rollback(ctx)

	doctorID, startsAt, err := claimSlot(ctx, tx, slotID, now)
	if err != nil {
		return domain.Appointment{}, err
	}
	id, err := insertAppointment(ctx, tx, slotID, patientID, doctorID, startsAt, now, nil)
	if err != nil {
		return domain.Appointment{}, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO scheduling.idempotency_keys (patient_id, key, appointment_id, created_at)
		VALUES ($1, $2, $3, $4)`, patientID, key, id, now); err != nil {
		if pgCode(err) == "23505" {
			return domain.Appointment{}, domain.ErrSlotTaken // same key raced; caller replays
		}
		return domain.Appointment{}, err
	}
	a, err := getAppt(ctx, tx, id)
	if err != nil {
		return a, err
	}
	if err := insertEvent(ctx, tx, domain.EventAppointmentBooked, domain.BookedEvent(a), now); err != nil {
		return a, err
	}
	return a, tx.Commit(ctx)
}

func cancelTx(ctx context.Context, tx pgx.Tx, id string, now time.Time) (domain.Appointment, error) {
	var slotID string
	err := tx.QueryRow(ctx, `UPDATE scheduling.appointments SET status = 'canceled', updated_at = $2
		WHERE id::text = $1 AND status = 'confirmed' RETURNING slot_id::text`, id, now).Scan(&slotID)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Appointment{}, domain.ErrNotConfirmed
	}
	if err != nil {
		return domain.Appointment{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE scheduling.slots SET status = 'open' WHERE id::text = $1`, slotID); err != nil {
		return domain.Appointment{}, err
	}
	return getAppt(ctx, tx, id)
}

func (r Repo) Cancel(ctx context.Context, id string, now time.Time) (domain.Appointment, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return domain.Appointment{}, err
	}
	defer tx.Rollback(ctx)
	a, err := cancelTx(ctx, tx, id, now)
	if err != nil {
		return a, err
	}
	if err := insertEvent(ctx, tx, domain.EventAppointmentCanceled, domain.CanceledEvent(a, "canceled"), now); err != nil {
		return a, err
	}
	return a, tx.Commit(ctx)
}

func (r Repo) Reschedule(ctx context.Context, id, newSlotID string, now time.Time) (domain.Appointment, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return domain.Appointment{}, err
	}
	defer tx.Rollback(ctx)
	old, err := cancelTx(ctx, tx, id, now)
	if err != nil {
		return old, err
	}
	doctorID, startsAt, err := claimSlot(ctx, tx, newSlotID, now)
	if err != nil {
		return domain.Appointment{}, err
	}
	newID, err := insertAppointment(ctx, tx, newSlotID, old.PatientID, doctorID, startsAt, now, &old.ID)
	if err != nil {
		return domain.Appointment{}, err
	}
	a, err := getAppt(ctx, tx, newID)
	if err != nil {
		return a, err
	}
	if err := insertEvent(ctx, tx, domain.EventAppointmentCanceled, domain.CanceledEvent(old, "rescheduled"), now); err != nil {
		return a, err
	}
	if err := insertEvent(ctx, tx, domain.EventAppointmentBooked, domain.BookedEvent(a), now); err != nil {
		return a, err
	}
	return a, tx.Commit(ctx)
}

func (r Repo) CheckIn(ctx context.Context, id string, now time.Time) (domain.Appointment, error) {
	tag, err := r.DB.Exec(ctx, `UPDATE scheduling.appointments SET status = 'attended', checked_in_at = $2, updated_at = $2
		WHERE id::text = $1 AND status = 'confirmed'`, id, now)
	if err != nil {
		return domain.Appointment{}, err
	}
	if tag.RowsAffected() == 0 {
		return domain.Appointment{}, domain.ErrNotConfirmed
	}
	return r.Get(ctx, id)
}

// ------------------------------------------------------------------- sweep

func (r Repo) MarkNoShows(ctx context.Context, cutoff, now time.Time) ([]domain.Appointment, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `UPDATE scheduling.appointments SET status = 'no_show', updated_at = $2
		WHERE status = 'confirmed' AND starts_at <= $1 RETURNING id::text`, cutoff, now)
	if err != nil {
		return nil, err
	}
	idsMarked, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return nil, err
	}
	var out []domain.Appointment
	for _, id := range idsMarked {
		a, err := getAppt(ctx, tx, id)
		if err != nil {
			return nil, err
		}
		if err := insertEvent(ctx, tx, domain.EventNoShowRecorded, domain.NoShowEvent(a), now); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, tx.Commit(ctx)
}

func (r Repo) RecordSweep(ctx context.Context, at time.Time, noShows int) error {
	_, err := r.DB.Exec(ctx, `INSERT INTO scheduling.sweep_state (id, last_swept_at, no_shows_total) VALUES (1, $1, $2)
		ON CONFLICT (id) DO UPDATE SET last_swept_at = EXCLUDED.last_swept_at,
		no_shows_total = scheduling.sweep_state.no_shows_total + EXCLUDED.no_shows_total`, at, noShows)
	return err
}

func (r Repo) LastSweep(ctx context.Context) (time.Time, bool, error) {
	var at time.Time
	err := r.DB.QueryRow(ctx, `SELECT last_swept_at FROM scheduling.sweep_state WHERE id = 1`).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) {
		return at, false, nil
	}
	return at, err == nil, err
}

// ------------------------------------------------------------------ outbox

func (r Repo) RelayOutbox(ctx context.Context, now time.Time, limit int, publish func(context.Context, domain.OutboxRecord) error) (int, int, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id, event_id::text, type, occurred_at, payload, attempts FROM scheduling.outbox
		WHERE delivered_at IS NULL AND next_attempt_at <= $1
		ORDER BY id LIMIT $2 FOR UPDATE SKIP LOCKED`, now, limit)
	if err != nil {
		return 0, 0, err
	}
	type row struct {
		id  int64
		rec domain.OutboxRecord
	}
	claimed, err := pgx.CollectRows(rows, func(cr pgx.CollectableRow) (row, error) {
		var x row
		return x, cr.Scan(&x.id, &x.rec.EventID, &x.rec.Type, &x.rec.OccurredAt, &x.rec.Payload, &x.rec.Attempts)
	})
	if err != nil {
		return 0, 0, err
	}
	delivered, failed := 0, 0
	for _, c := range claimed {
		if perr := publish(ctx, c.rec); perr != nil {
			backoff := time.Duration(math.Min(300, math.Pow(2, float64(c.rec.Attempts)))) * time.Second
			if _, err := tx.Exec(ctx, `UPDATE scheduling.outbox SET attempts = attempts + 1, next_attempt_at = $2, last_error = $3 WHERE id = $1`,
				c.id, now.Add(backoff), truncate(perr.Error(), 500)); err != nil {
				return delivered, failed, err
			}
			failed++
			continue
		}
		if _, err := tx.Exec(ctx, `UPDATE scheduling.outbox SET attempts = attempts + 1, delivered_at = $2, last_error = NULL WHERE id = $1`, c.id, now); err != nil {
			return delivered, failed, err
		}
		delivered++
	}
	return delivered, failed, tx.Commit(ctx)
}

func (r Repo) OutboxStats(ctx context.Context) (int, error) {
	var n int
	err := r.DB.QueryRow(ctx, `SELECT count(*) FROM scheduling.outbox WHERE delivered_at IS NULL`).Scan(&n)
	return n, err
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

