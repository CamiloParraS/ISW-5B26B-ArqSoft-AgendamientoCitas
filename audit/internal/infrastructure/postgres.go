// Package infrastructure stores the Access log in PostgreSQL. The audit_app
// role only has INSERT and SELECT, so even a bug here cannot rewrite history.
package infrastructure

import (
	"context"

	"clinic/audit/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Log struct{ DB *pgxpool.Pool }

var _ domain.Log = Log{}

func (l Log) Append(ctx context.Context, e domain.Entry) error {
	var patient any
	if e.PatientID != "" {
		patient = e.PatientID
	}
	_, err := l.DB.Exec(ctx, `INSERT INTO audit.access_log
		(at, user_id, user_name, role, action, resource, patient_id, allowed, reason, source)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		e.At, e.UserID, e.UserName, e.Role, e.Action, e.Resource, patient, e.Allowed, e.Reason, e.Source)
	return err
}

func (l Log) Recent(ctx context.Context, limit int) ([]domain.Entry, error) {
	rows, err := l.DB.Query(ctx, `SELECT id, at, user_id, user_name, role, action, resource,
		coalesce(patient_id, ''), allowed, reason, source
		FROM audit.access_log ORDER BY id DESC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Entry, error) {
		var e domain.Entry
		return e, row.Scan(&e.ID, &e.At, &e.UserID, &e.UserName, &e.Role, &e.Action, &e.Resource,
			&e.PatientID, &e.Allowed, &e.Reason, &e.Source)
	})
}
