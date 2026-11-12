// Package testdb gives integration tests a migrated, seeded, empty-of-traffic
// database. It uses TEST_DATABASE_URL (owner credentials) and skips the test
// when PostgreSQL is not reachable. Run with `go test -p 1` (see Makefile):
// every test resets the same database.
package testdb

import (
	"context"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"clinic/internal/app"
	"clinic/internal/migrations"
	"clinic/internal/platform/clock"
	"clinic/internal/seed"

	"github.com/jackc/pgx/v5/pgxpool"
)

const DefaultURL = "postgres://clinic_owner:clinic_owner_dev@localhost:55432/clinic_test?sslmode=disable"

// Key is the clinical key the tests seal and open encounters with.
var Key, _ = hex.DecodeString(strings.Repeat("ab", 32))

func URL() string {
	if v := os.Getenv("TEST_DATABASE_URL"); v != "" {
		return v
	}
	return DefaultURL
}

// Owner connects as the database owner, applies migrations and wipes every
// module table. The test is skipped when the database is unreachable.
func Owner(t testing.TB) *pgxpool.Pool {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, URL())
	if err == nil {
		err = db.Ping(ctx)
	}
	if err != nil {
		if os.Getenv("REQUIRE_DB") != "" {
			t.Fatalf("database not reachable at %s: %v", URL(), err)
		}
		t.Skipf("database not reachable (%v); start it with `docker compose up -d postgres`", err)
	}
	t.Cleanup(db.Close)
	if err := migrations.EnsureRoles(ctx, db); err != nil {
		t.Fatal(err)
	}
	if _, err := migrations.Apply(ctx, db); err != nil {
		t.Fatal(err)
	}
	rows, err := db.Query(ctx, `SELECT schemaname || '.' || tablename FROM pg_tables
		WHERE schemaname IN ('identity','scheduling','clinical','notifications','audit')`)
	if err != nil {
		t.Fatal(err)
	}
	var tables []string
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		tables = append(tables, s)
	}
	rows.Close()
	if _, err := db.Exec(ctx, fmt.Sprintf("TRUNCATE %s RESTART IDENTITY CASCADE", strings.Join(tables, ", "))); err != nil {
		t.Fatal(err)
	}
	return db
}

// App returns a seeded application on a Fixed clock, with a provider that
// accepts every message. The seed's "now" is the clock's now.
func App(t testing.TB) (*app.App, *clock.Fixed, *pgxpool.Pool) {
	t.Helper()
	owner := Owner(t)
	clk := clock.NewFixed(time.Now().UTC().Truncate(time.Second))
	if _, err := seed.Run(context.Background(), owner, Key, clk.Now()); err != nil {
		t.Fatal(err)
	}
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusAccepted)
	}))
	t.Cleanup(provider.Close)
	cfg, err := app.ConfigFromStrings(URL(), "test-token-secret-0123", "tick", hex.EncodeToString(Key), provider.URL, true)
	if err != nil {
		t.Fatal(err)
	}
	a, err := app.New(context.Background(), cfg, clk)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.Close)
	return a, clk, owner
}
