// Command migrate runs once, as the database owner: it creates the per-module
// roles, applies the schema and (with -seed) loads the demo data. The app never
// holds these credentials.
package main

import (
	"context"
	"encoding/hex"
	"flag"
	"log/slog"
	"os"
	"time"

	"clinic/internal/migrations"
	"clinic/internal/seed"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	doSeed := flag.Bool("seed", false, "load demo data if the database is empty")
	flag.Parse()
	url := os.Getenv("OWNER_DATABASE_URL")
	if url == "" {
		slog.Error("OWNER_DATABASE_URL is required")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	db, err := connect(ctx, url)
	if err != nil {
		slog.Error("connect", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	if err := migrations.EnsureRoles(ctx, db); err != nil {
		slog.Error("roles", "err", err)
		os.Exit(1)
	}
	applied, err := migrations.Apply(ctx, db)
	if err != nil {
		slog.Error("migrate", "err", err)
		os.Exit(1)
	}
	slog.Info("migrations applied", "new", applied)

	if *doSeed {
		key, err := hex.DecodeString(os.Getenv("CLINICAL_KEY"))
		if err != nil || len(key) != 32 {
			slog.Error("CLINICAL_KEY must be 64 hex characters")
			os.Exit(2)
		}
		seeded, err := seed.Run(ctx, db, key, time.Now())
		if err != nil {
			slog.Error("seed", "err", err)
			os.Exit(1)
		}
		slog.Info("seed", "loaded", seeded)
	}
}

// connect retries while Postgres starts up.
func connect(ctx context.Context, url string) (*pgxpool.Pool, error) {
	var last error
	for ctx.Err() == nil {
		db, err := pgxpool.New(ctx, url)
		if err == nil {
			if err = db.Ping(ctx); err == nil {
				return db, nil
			}
			db.Close()
		}
		last = err
		slog.Info("waiting for postgres", "err", err)
		time.Sleep(time.Second)
	}
	return nil, last
}
