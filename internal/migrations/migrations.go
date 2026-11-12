// Package migrations creates the per-module roles and applies the schema.
// It runs only as the database owner (cmd/migrate and tests); the app never
// holds owner credentials.
package migrations

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed sql/*.sql
var files embed.FS

// Modules are the five modules, each with its own schema and <module>_app role.
var Modules = []string{"identity", "scheduling", "clinical", "notifications", "audit"}

// RolePassword returns the password for a module's role: env <MODULE>_DB_PASSWORD,
// or the development default "<module>_dev".
func RolePassword(module string) string {
	if v := os.Getenv(strings.ToUpper(module) + "_DB_PASSWORD"); v != "" {
		return v
	}
	return module + "_dev"
}

// EnsureRoles creates (or resets the password of) every <module>_app login role.
func EnsureRoles(ctx context.Context, db *pgxpool.Pool) error {
	for _, m := range Modules {
		role := m + "_app"
		pw := strings.ReplaceAll(RolePassword(m), "'", "''")
		var exists bool
		if err := db.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)`, role).Scan(&exists); err != nil {
			return err
		}
		stmt := fmt.Sprintf(`CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE`, role, pw)
		if exists {
			stmt = fmt.Sprintf(`ALTER ROLE %s LOGIN PASSWORD '%s'`, role, pw)
		}
		if _, err := db.Exec(ctx, stmt); err != nil {
			return fmt.Errorf("role %s: %w", role, err)
		}
		// Roles are cluster-wide; allow connecting to this database only.
		var dbname string
		if err := db.QueryRow(ctx, `SELECT current_database()`).Scan(&dbname); err != nil {
			return err
		}
		if _, err := db.Exec(ctx, fmt.Sprintf(`GRANT CONNECT ON DATABASE %s TO %s`, pgx.Identifier{dbname}.Sanitize(), role)); err != nil {
			return err
		}
	}
	return nil
}

// Apply runs every not-yet-applied migration in order, each in its own transaction.
func Apply(ctx context.Context, db *pgxpool.Pool) ([]string, error) {
	if _, err := db.Exec(ctx, `CREATE TABLE IF NOT EXISTS public.schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return nil, err
	}
	names, err := fs.Glob(files, "sql/*.sql")
	if err != nil {
		return nil, err
	}
	sort.Strings(names)
	var applied []string
	for _, name := range names {
		var done bool
		if err := db.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM public.schema_migrations WHERE name = $1)`, name).Scan(&done); err != nil {
			return applied, err
		}
		if done {
			continue
		}
		body, err := files.ReadFile(name)
		if err != nil {
			return applied, err
		}
		tx, err := db.Begin(ctx)
		if err != nil {
			return applied, err
		}
		if _, err := tx.Exec(ctx, string(body)); err != nil {
			_ = tx.Rollback(ctx)
			return applied, fmt.Errorf("%s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO public.schema_migrations (name) VALUES ($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return applied, err
		}
		if err := tx.Commit(ctx); err != nil {
			return applied, err
		}
		applied = append(applied, name)
	}
	return applied, nil
}

// ModuleDSN builds the connection string a module's runtime role uses,
// by swapping the user and password of an owner/base URL.
func ModuleDSN(baseURL, module string) (string, error) {
	cfg, err := pgx.ParseConfig(baseURL)
	if err != nil {
		return "", err
	}
	u := url.URL{Scheme: "postgres", User: url.UserPassword(module+"_app", RolePassword(module)),
		Host: fmt.Sprintf("%s:%d", cfg.Host, cfg.Port), Path: "/" + cfg.Database, RawQuery: "sslmode=disable"}
	return u.String(), nil
}
