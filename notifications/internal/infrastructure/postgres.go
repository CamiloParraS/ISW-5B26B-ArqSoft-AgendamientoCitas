// Package infrastructure implements Notifications' ports: PostgreSQL storage
// and the HTTP client for the Messaging provider.
package infrastructure

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"clinic/notifications/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Repo struct{ DB *pgxpool.Pool }

var _ domain.Repository = Repo{}

func (r Repo) Recipient(ctx context.Context, patientID string) (domain.Recipient, error) {
	var rc domain.Recipient
	err := r.DB.QueryRow(ctx, `SELECT patient_id::text, name, channel, address FROM notifications.recipients
		WHERE patient_id::text = $1`, patientID).Scan(&rc.PatientID, &rc.Name, &rc.Channel, &rc.Address)
	if errors.Is(err, pgx.ErrNoRows) {
		// Unknown Recipient: the Message is still recorded, the provider will reject it.
		return domain.Recipient{PatientID: patientID, Channel: "sms", Address: "unknown"}, nil
	}
	return rc, err
}

func (r Repo) ApplyOnce(ctx context.Context, eventID string, now time.Time, e domain.Effect) (bool, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `INSERT INTO notifications.inbox (event_id, processed_at) VALUES ($1, $2)
		ON CONFLICT DO NOTHING`, eventID, now)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		return false, nil // already processed: at-least-once delivery, exactly-once effect
	}
	if e.CancelPendingFor != "" {
		if _, err := tx.Exec(ctx, `UPDATE notifications.messages SET status = 'canceled'
			WHERE appointment_id::text = $1 AND status = 'pending' AND kind IN ('reminder','confirmation')`, e.CancelPendingFor); err != nil {
			return false, err
		}
	}
	for _, m := range e.Enqueue {
		if _, err := tx.Exec(ctx, `INSERT INTO notifications.messages
			(appointment_id, patient_id, kind, channel, address, body, send_at, next_attempt_at, created_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (appointment_id, kind) DO NOTHING`,
			m.AppointmentID, m.PatientID, m.Kind, m.Channel, m.Address, m.Body, m.SendAt, m.NextAttemptAt, now); err != nil {
			return false, err
		}
	}
	return true, tx.Commit(ctx)
}

const msgCols = `id::text, appointment_id::text, patient_id::text, kind, channel, address, body, send_at, status,
	attempts, next_attempt_at, last_error, sent_at, created_at`

func scanMsg(row pgx.Row) (domain.Message, error) {
	var m domain.Message
	err := row.Scan(&m.ID, &m.AppointmentID, &m.PatientID, &m.Kind, &m.Channel, &m.Address, &m.Body, &m.SendAt,
		&m.Status, &m.Attempts, &m.NextAttemptAt, &m.LastError, &m.SentAt, &m.CreatedAt)
	return m, err
}

// ClaimDue pushes next_attempt_at forward by the lease inside the claiming
// statement, so the drain loop and a Tick never send the same Message twice.
func (r Repo) ClaimDue(ctx context.Context, now time.Time, lease time.Duration, limit int) ([]domain.Message, error) {
	rows, err := r.DB.Query(ctx, `UPDATE notifications.messages SET next_attempt_at = $2
		WHERE id IN (SELECT id FROM notifications.messages
			WHERE status = 'pending' AND next_attempt_at <= $1
			ORDER BY next_attempt_at LIMIT $3 FOR UPDATE SKIP LOCKED)
		RETURNING `+msgCols, now, now.Add(lease), limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Message, error) { return scanMsg(row) })
}

func (r Repo) MarkSent(ctx context.Context, id string, now time.Time) error {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE notifications.messages SET status = 'sent', sent_at = $2, attempts = attempts + 1, last_error = NULL
		WHERE id::text = $1`, id, now); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO notifications.delivery_attempts (message_id, at, ok) VALUES ($1, $2, true)`, id, now); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r Repo) MarkAttemptFailed(ctx context.Context, id string, now, next time.Time, giveUp bool, reason string) error {
	status := "pending"
	if giveUp {
		status = "failed"
	}
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE notifications.messages SET status = $2, attempts = attempts + 1, next_attempt_at = $3, last_error = $4
		WHERE id::text = $1`, id, status, next, reason); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO notifications.delivery_attempts (message_id, at, ok, error) VALUES ($1, $2, false, $3)`, id, now, reason); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r Repo) List(ctx context.Context, patientID string, limit int) ([]domain.Message, error) {
	rows, err := r.DB.Query(ctx, `SELECT `+msgCols+` FROM notifications.messages
		WHERE ($1 = '' OR patient_id::text = $1) ORDER BY created_at DESC, kind LIMIT $2`, patientID, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Message, error) { return scanMsg(row) })
}

// --------------------------------------------------------------- provider

// HTTPProvider posts Messages to the Messaging provider.
type HTTPProvider struct {
	BaseURL string
	Client  *http.Client
}

func (p HTTPProvider) Send(ctx context.Context, m domain.Message) error {
	body, _ := json.Marshal(map[string]string{
		"message_id": m.ID, "channel": m.Channel, "to": m.Address, "body": m.Body, "kind": string(m.Kind),
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.BaseURL+"/messages", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", m.ID)
	resp, err := p.Client.Do(req)
	if err != nil {
		return fmt.Errorf("provider unreachable: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 200))
		return fmt.Errorf("provider answered %d: %s", resp.StatusCode, bytes.TrimSpace(b))
	}
	return nil
}
