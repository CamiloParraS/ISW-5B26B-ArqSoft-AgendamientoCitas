// Package infrastructure stores Clinical data in PostgreSQL, encrypting
// Diagnosis and Prescription at the column level with AES-256-GCM (T9).
package infrastructure

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"time"

	"clinic/clinical/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Cipher seals and opens sensitive columns. Output is nonce || ciphertext.
type Cipher struct{ aead cipher.AEAD }

func NewCipher(key []byte) (Cipher, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return Cipher{}, err
	}
	aead, err := cipher.NewGCM(block)
	return Cipher{aead}, err
}

func (c Cipher) Seal(plain string) []byte {
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		panic(err)
	}
	return c.aead.Seal(nonce, nonce, []byte(plain), nil)
}

func (c Cipher) Open(sealed []byte) (string, error) {
	n := c.aead.NonceSize()
	if len(sealed) < n {
		return "", errors.New("ciphertext too short")
	}
	plain, err := c.aead.Open(nil, sealed[:n], sealed[n:], nil)
	return string(plain), err
}

type Repo struct {
	DB     *pgxpool.Pool
	Cipher Cipher
}

var _ domain.Repository = Repo{}

func (r Repo) HasRelationship(ctx context.Context, doctorID, patientID string) (bool, error) {
	var ok bool
	err := r.DB.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM clinical.treatment_relationships
		WHERE doctor_id::text = $1 AND patient_id::text = $2)`, doctorID, patientID).Scan(&ok)
	return ok, err
}

func (r Repo) Encounters(ctx context.Context, patientID string) ([]domain.Encounter, error) {
	rows, err := r.DB.Query(ctx, `SELECT id::text, patient_id::text, doctor_id::text, doctor_name, occurred_at,
		diagnosis_enc, prescription_enc, version, supersedes::text
		FROM clinical.encounters WHERE patient_id::text = $1 ORDER BY occurred_at DESC`, patientID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (domain.Encounter, error) {
		var e domain.Encounter
		var dx, rx []byte
		if err := row.Scan(&e.ID, &e.PatientID, &e.DoctorID, &e.DoctorName, &e.OccurredAt, &dx, &rx, &e.Version, &e.Supersedes); err != nil {
			return e, err
		}
		var err error
		if e.Diagnosis, err = r.Cipher.Open(dx); err != nil {
			return e, err
		}
		e.Prescription, err = r.Cipher.Open(rx)
		return e, err
	})
}

func (r Repo) AddEncounter(ctx context.Context, e domain.Encounter) (domain.Encounter, error) {
	err := r.DB.QueryRow(ctx, `INSERT INTO clinical.encounters
		(patient_id, doctor_id, doctor_name, occurred_at, diagnosis_enc, prescription_enc, version, supersedes)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id::text`,
		e.PatientID, e.DoctorID, e.DoctorName, e.OccurredAt, r.Cipher.Seal(e.Diagnosis), r.Cipher.Seal(e.Prescription),
		e.Version, e.Supersedes).Scan(&e.ID)
	return e, err
}

func (r Repo) ApplyOnce(ctx context.Context, eventID string, now time.Time, add *domain.Relationship, removeAppt string) (bool, error) {
	tx, err := r.DB.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `INSERT INTO clinical.inbox (event_id, processed_at) VALUES ($1, $2) ON CONFLICT DO NOTHING`, eventID, now)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		return false, nil
	}
	if add != nil {
		if _, err := tx.Exec(ctx, `INSERT INTO clinical.treatment_relationships (appointment_id, doctor_id, patient_id, created_at)
			VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING`, add.AppointmentID, add.DoctorID, add.PatientID, now); err != nil {
			return false, err
		}
	}
	if removeAppt != "" {
		if _, err := tx.Exec(ctx, `DELETE FROM clinical.treatment_relationships WHERE appointment_id::text = $1`, removeAppt); err != nil {
			return false, err
		}
	}
	return true, tx.Commit(ctx)
}
