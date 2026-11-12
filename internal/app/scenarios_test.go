package app_test

// The report's quality scenarios as tests. Each one goes through the modules'
// public interfaces, the same way the HTTP handlers do.

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"clinic/clinical"
	"clinic/internal/migrations"
	"clinic/internal/platform/ids"
	"clinic/internal/seed"
	"clinic/internal/testdb"
	"clinic/scheduling"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// E-1: 50 patients book the same slot at the same instant, 100 times over.
// Exactly one booking wins each time; the other 49 get ErrSlotTaken.
func TestE1ConcurrentBooking(t *testing.T) {
	a, clk, owner := testdb.App(t)
	ctx := context.Background()
	runs := 100
	if testing.Short() {
		runs = 10
	}
	base := seed.NextMonday(clk.Now()).Add(13 * time.Hour) // afternoon: no seeded slots there
	for run := 0; run < runs; run++ {
		slot, err := a.Scheduling.OpenSlot(ctx, seed.DrJeferson, base.Add(time.Duration(run)*5*time.Minute))
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		gate := make(chan struct{})
		results := make([]error, 50)
		for i := 0; i < 50; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				<-gate
				_, results[i] = a.Scheduling.Book(ctx, seed.BurstPatient(i+1), slot.ID, ids.New())
			}(i)
		}
		close(gate)
		wg.Wait()
		won, taken := 0, 0
		for _, err := range results {
			switch {
			case err == nil:
				won++
			case errors.Is(err, scheduling.ErrSlotTaken):
				taken++
			default:
				t.Fatalf("run %d: unexpected error: %v", run, err)
			}
		}
		var live int
		if err := owner.QueryRow(ctx, `SELECT count(*) FROM scheduling.appointments WHERE slot_id = $1 AND status <> 'canceled'`, slot.ID).Scan(&live); err != nil {
			t.Fatal(err)
		}
		if won != 1 || taken != 49 || live != 1 {
			t.Fatalf("run %d: confirmed=%d taken=%d rows=%d, want 1/49/1", run, won, taken, live)
		}
	}
}

// T3: repeating a booking with the same Idempotency-Key returns the same
// Appointment instead of a second one or a slot-taken error.
func TestBookingIsIdempotent(t *testing.T) {
	a, clk, _ := testdb.App(t)
	ctx := context.Background()
	slot, err := a.Scheduling.OpenSlot(ctx, seed.DrCamilo, seed.NextMonday(clk.Now()).Add(14*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	key := ids.New()
	first, err := a.Scheduling.Book(ctx, seed.Luis, slot.ID, key)
	if err != nil {
		t.Fatal(err)
	}
	again, err := a.Scheduling.Book(ctx, seed.Luis, slot.ID, key)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if again.Appointment.ID != first.Appointment.ID || again.Created {
		t.Fatalf("replay created a new appointment: %+v", again)
	}
}

// E-2: only a doctor with a treatment relationship reads Ana's diagnoses, and
// every attempt, allowed or not, is in the access log.
func TestE2ClinicalAccess(t *testing.T) {
	a, _, _ := testdb.App(t)
	ctx := context.Background()
	cases := []struct {
		name string
		r    clinical.Reader
		ok   bool
	}{
		{"treating doctor", clinical.Reader{UserID: ids.New(), UserName: "Dr. Jeferson", Role: "doctor", SubjectID: seed.DrJeferson}, true},
		{"other doctor", clinical.Reader{UserID: ids.New(), UserName: "Dr. Camilo", Role: "doctor", SubjectID: seed.DrCamilo}, false},
		{"staff", clinical.Reader{UserID: ids.New(), UserName: "Staff", Role: "staff"}, false},
		{"the patient", clinical.Reader{UserID: ids.New(), UserName: "Ana", Role: "patient", SubjectID: seed.Ana}, true},
		{"another patient", clinical.Reader{UserID: ids.New(), UserName: "Luis", Role: "patient", SubjectID: seed.Luis}, false},
	}
	for _, c := range cases {
		encs, err := a.Clinical.ReadRecord(ctx, c.r, seed.Ana)
		if c.ok {
			if err != nil || len(encs) == 0 || encs[0].Diagnosis == "" {
				t.Errorf("%s: want the decrypted record, got %v (%d encounters)", c.name, err, len(encs))
			}
		} else if !errors.Is(err, clinical.ErrForbidden) || encs != nil {
			t.Errorf("%s: want ErrForbidden and no data, got %v", c.name, err)
		}
	}
	entries, err := a.Audit.Recent(ctx, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != len(cases) {
		t.Fatalf("access log has %d entries, want %d", len(entries), len(cases))
	}
	for _, e := range entries {
		if e.PatientID != seed.Ana || e.Action != "read" {
			t.Errorf("unexpected entry %+v", e)
		}
	}
}

// E-2 continued: the relationship comes from Scheduling's events. Booking Ana
// with Dr. Camilo lets him read; canceling it takes the permission away.
func TestRelationshipFollowsEvents(t *testing.T) {
	a, clk, _ := testdb.App(t)
	ctx := context.Background()
	camilo := clinical.Reader{UserID: ids.New(), UserName: "Dr. Camilo", Role: "doctor", SubjectID: seed.DrCamilo}
	slot, err := a.Scheduling.OpenSlot(ctx, seed.DrCamilo, seed.NextMonday(clk.Now()).Add(15*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	booked, err := a.Scheduling.Book(ctx, seed.Ana, slot.ID, ids.New())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.Tick(ctx); err != nil { // relays AppointmentBooked
		t.Fatal(err)
	}
	if _, err := a.Clinical.ReadRecord(ctx, camilo, seed.Ana); err != nil {
		t.Fatalf("after booking: %v", err)
	}
	if _, err := a.Scheduling.Cancel(ctx, seed.Ana, booked.Appointment.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Tick(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Clinical.ReadRecord(ctx, camilo, seed.Ana); !errors.Is(err, clinical.ErrForbidden) {
		t.Fatalf("after cancel: want ErrForbidden, got %v", err)
	}
}

// E-4: an appointment passes its 15-minute grace while the app is asleep. The
// next Tick marks one no-show and queues one message; a second Tick does nothing.
func TestE4NoShowSweepRunsOnce(t *testing.T) {
	a, clk, _ := testdb.App(t)
	ctx := context.Background()
	slot, err := a.Scheduling.OpenSlot(ctx, seed.DrJuanDieg, clk.Now().Add(5*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	booked, err := a.Scheduling.Book(ctx, seed.Luis, slot.ID, ids.New())
	if err != nil {
		t.Fatal(err)
	}
	clk.Advance(5*time.Minute + 15*time.Minute + time.Minute)
	first, err := a.Tick(ctx)
	if err != nil {
		t.Fatal(err)
	}
	second, err := a.Tick(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if first.NoShows != 1 || second.NoShows != 0 {
		t.Fatalf("no-shows per tick = %d, %d; want 1, 0", first.NoShows, second.NoShows)
	}
	msgs, err := a.Notifications.Messages(ctx, seed.Luis)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, m := range msgs {
		if m.AppointmentID == booked.Appointment.ID && m.Kind == "no_show" {
			n++
			if m.Status != "sent" {
				t.Errorf("no-show message status %q, want sent", m.Status)
			}
		}
	}
	if n != 1 {
		t.Fatalf("%d no-show messages, want exactly 1", n)
	}
}

// The 24-hour rule follows the injected clock.
func TestCancellationNeeds24Hours(t *testing.T) {
	a, clk, _ := testdb.App(t)
	ctx := context.Background()
	slot, err := a.Scheduling.OpenSlot(ctx, seed.DrCamilo, clk.Now().Add(30*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	booked, err := a.Scheduling.Book(ctx, seed.Luis, slot.ID, ids.New())
	if err != nil {
		t.Fatal(err)
	}
	clk.Advance(7 * time.Hour) // 23 h left
	if _, err := a.Scheduling.Cancel(ctx, seed.Luis, booked.Appointment.ID); !errors.Is(err, scheduling.ErrTooLate) {
		t.Fatalf("want ErrTooLate, got %v", err)
	}
}

// T7: each module's database role reaches only its own schema, the access log
// is append-only, and clinical encounters cannot be deleted.
func TestDBRoleBoundaries(t *testing.T) {
	testdb.Owner(t)
	ctx := context.Background()
	connect := func(module string) *pgxpool.Pool {
		dsn, err := migrations.ModuleDSN(testdb.URL(), module)
		if err != nil {
			t.Fatal(err)
		}
		p, err := pgxpool.New(ctx, dsn)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(p.Close)
		return p
	}
	denied := []struct{ module, sql string }{
		{"scheduling", `SELECT count(*) FROM clinical.encounters`},
		{"scheduling", `SELECT count(*) FROM clinical.treatment_relationships`},
		{"notifications", `SELECT count(*) FROM clinical.encounters`},
		{"identity", `SELECT count(*) FROM scheduling.appointments`},
		{"clinical", `SELECT count(*) FROM scheduling.appointments`},
		{"clinical", `DELETE FROM clinical.encounters`},
		{"audit", `UPDATE audit.access_log SET allowed = true`},
		{"audit", `DELETE FROM audit.access_log`},
	}
	for _, d := range denied {
		_, err := connect(d.module).Exec(ctx, d.sql)
		var pg *pgconn.PgError
		if !errors.As(err, &pg) || pg.Code != "42501" {
			t.Errorf("%s_app: %q should fail with insufficient_privilege (42501), got %v", d.module, d.sql, err)
		}
	}
	if _, err := connect("clinical").Exec(ctx, `SELECT count(*) FROM clinical.encounters`); err != nil {
		t.Errorf("clinical_app must read its own schema: %v", err)
	}
}
