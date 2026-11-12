// Package seed loads the demo data the report's scenarios name: Dr. Jeferson
// (Cardiology, Monday 8:00, E-1), Dr. Camilo (Orthopedics, no relationship with
// Ana, E-2), Ana with an existing Clinical record, Staff, the DPO and 50 burst
// patients. It runs as the database owner, once.
package seed

import (
	"context"
	"fmt"
	"time"

	"clinic/clinical"
	"clinic/identity"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Fixed ids make demo scripts and tests readable.
const (
	DrJeferson = "00000000-0000-4000-8000-0000000000d1"
	DrCamilo   = "00000000-0000-4000-8000-0000000000d2"
	DrJuanDieg = "00000000-0000-4000-8000-0000000000d3"
	Ana        = "00000000-0000-4000-8000-0000000000a1"
	Luis       = "00000000-0000-4000-8000-0000000000a2"
)

func BurstPatient(i int) string { return fmt.Sprintf("00000000-0000-4000-8000-000000000b%02d", i) }

var Bogota = func() *time.Location {
	if loc, err := time.LoadLocation("America/Bogota"); err == nil {
		return loc
	}
	return time.FixedZone("COT", -5*3600)
}()

// NextMonday returns the Monday after now, at 00:00 Bogota time.
func NextMonday(now time.Time) time.Time {
	d := now.In(Bogota)
	days := (8 - int(d.Weekday())) % 7
	if days == 0 {
		days = 7
	}
	return time.Date(d.Year(), d.Month(), d.Day()+days, 0, 0, 0, 0, Bogota)
}

// Run seeds if identity.users is empty. Returns false when data already existed.
func Run(ctx context.Context, db *pgxpool.Pool, clinicalKey []byte, now time.Time) (bool, error) {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM identity.users`).Scan(&n); err != nil {
		return false, err
	}
	if n > 0 {
		return false, nil
	}
	tx, err := db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)

	hash, err := identity.HashPassword("demo")
	if err != nil {
		return false, err
	}
	exec := func(sql string, args ...any) {
		if err != nil {
			return
		}
		_, err = tx.Exec(ctx, sql, args...)
	}

	doctors := []struct{ id, user, name, specialty string }{
		{DrJeferson, "jeferson", "Dr. Jeferson Morillo", "Cardiology"},
		{DrCamilo, "camilo", "Dr. Camilo Parra", "Orthopedics"},
		{DrJuanDieg, "juandiego", "Dr. Juan Diego Lopez", "General medicine"},
	}
	for _, d := range doctors {
		exec(`INSERT INTO scheduling.doctors (id, name, specialty) VALUES ($1, $2, $3)`, d.id, d.name, d.specialty)
		exec(`INSERT INTO identity.users (username, password_hash, role, subject_id, display_name) VALUES ($1, $2, 'doctor', $3, $4)`,
			d.user, hash, d.id, d.name)
	}

	type patient struct{ id, user, name, doc, phone string }
	patients := []patient{
		{Ana, "ana", "Ana Gomez", "CC-1085001", "+57 300 111 0001"},
		{Luis, "luis", "Luis Martinez", "CC-1085002", "+57 300 111 0002"},
	}
	for i := 1; i <= 50; i++ {
		patients = append(patients, patient{BurstPatient(i), fmt.Sprintf("patient%02d", i),
			fmt.Sprintf("Burst patient %02d", i), fmt.Sprintf("CC-99%04d", i), fmt.Sprintf("+57 310 000 %04d", i)})
	}
	for _, p := range patients {
		exec(`INSERT INTO scheduling.patients (id, name, document, phone) VALUES ($1, $2, $3, $4)`, p.id, p.name, p.doc, p.phone)
		exec(`INSERT INTO notifications.recipients (patient_id, name, channel, address) VALUES ($1, $2, 'sms', $3)`, p.id, p.name, p.phone)
		exec(`INSERT INTO identity.users (username, password_hash, role, subject_id, display_name) VALUES ($1, $2, 'patient', $3, $4)`,
			p.user, hash, p.id, p.name)
	}
	exec(`INSERT INTO identity.users (username, password_hash, role, display_name) VALUES ('staff', $1, 'staff', 'Marta (front desk)')`, hash)
	exec(`INSERT INTO identity.users (username, password_hash, role, display_name) VALUES ('dpo', $1, 'dpo', 'Data Protection Officer')`, hash)

	// Slots: Monday to Friday next week, 07:00-12:00 every 20 minutes, every doctor.
	monday := NextMonday(now)
	batch := &pgx.Batch{}
	for day := range 5 {
		for _, d := range doctors {
			for m := 7 * 60; m < 12*60; m += 20 {
				at := monday.AddDate(0, 0, day).Add(time.Duration(m) * time.Minute)
				batch.Queue(`INSERT INTO scheduling.slots (doctor_id, starts_at) VALUES ($1, $2)`, d.id, at)
			}
		}
	}
	if err != nil {
		return false, err
	}
	if err = tx.SendBatch(ctx, batch).Close(); err != nil {
		return false, err
	}

	// Ana's history: an attended Appointment with Dr. Jeferson three weeks ago,
	// the Treatment relationship it produced, and the Encounter he recorded.
	past := time.Date(now.In(Bogota).Year(), now.In(Bogota).Month(), now.In(Bogota).Day()-21, 9, 0, 0, 0, Bogota)
	const pastSlot, pastAppt = "00000000-0000-4000-8000-0000000005a1", "00000000-0000-4000-8000-0000000000e1"
	exec(`INSERT INTO scheduling.slots (id, doctor_id, starts_at, status) VALUES ($1, $2, $3, 'booked')`, pastSlot, DrJeferson, past)
	exec(`INSERT INTO scheduling.appointments (id, slot_id, patient_id, doctor_id, starts_at, status, checked_in_at, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, 'attended', $5, $6, $5)`, pastAppt, pastSlot, Ana, DrJeferson, past, past.AddDate(0, 0, -7))
	exec(`INSERT INTO clinical.treatment_relationships (appointment_id, doctor_id, patient_id, created_at) VALUES ($1, $2, $3, $4)`,
		pastAppt, DrJeferson, Ana, past.AddDate(0, 0, -7))
	dx, err2 := clinical.Seal(clinicalKey, "Stage 1 essential hypertension (I10). BP 142/91.")
	rx, err3 := clinical.Seal(clinicalKey, "Losartan 50 mg once daily; low-sodium diet; control in 4 weeks.")
	if err2 != nil || err3 != nil {
		return false, fmt.Errorf("seal: %v %v", err2, err3)
	}
	exec(`INSERT INTO clinical.encounters (patient_id, doctor_id, doctor_name, occurred_at, diagnosis_enc, prescription_enc)
		VALUES ($1, $2, 'Dr. Jeferson Morillo', $3, $4, $5)`, Ana, DrJeferson, past.Add(25*time.Minute), dx, rx)
	if err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
