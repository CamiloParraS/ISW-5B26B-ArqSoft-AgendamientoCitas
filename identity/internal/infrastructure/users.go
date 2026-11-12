// Package infrastructure implements Identity's ports on PostgreSQL.
package infrastructure

import (
	"context"
	"errors"

	"clinic/identity/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Users struct{ DB *pgxpool.Pool }

const userCols = `id::text, username, password_hash, role, coalesce(subject_id::text, ''), display_name`

func scanUser(row pgx.Row) (domain.User, error) {
	var u domain.User
	err := row.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Role, &u.SubjectID, &u.DisplayName)
	return u, err
}

func (r Users) ByUsername(ctx context.Context, username string) (domain.User, error) {
	u, err := scanUser(r.DB.QueryRow(ctx, `SELECT `+userCols+` FROM identity.users WHERE username = $1`, username))
	if errors.Is(err, pgx.ErrNoRows) {
		return u, domain.ErrNotFound
	}
	return u, err
}

func (r Users) List(ctx context.Context) ([]domain.User, error) {
	rows, err := r.DB.Query(ctx, `SELECT `+userCols+` FROM identity.users
		WHERE username NOT LIKE 'patient__' ORDER BY
		CASE role WHEN 'patient' THEN 1 WHEN 'doctor' THEN 2 WHEN 'staff' THEN 3 ELSE 4 END, display_name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.User
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}
