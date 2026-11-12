// Package domain holds Identity's model: Users, Credentials and Access tokens.
package domain

import (
	"context"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrInvalidToken       = errors.New("invalid or expired access token")
	ErrNotFound           = errors.New("user not found")
)

// User is a login with exactly one role. It refers to a Patient or Doctor via SubjectID.
type User struct {
	ID           string
	Username     string
	PasswordHash string
	Role         string
	SubjectID    string
	DisplayName  string
}

// Users is the port to stored users.
type Users interface {
	ByUsername(ctx context.Context, username string) (User, error)
	List(ctx context.Context) ([]User, error)
}

// ---------------------------------------------------------------- credentials

const pbkdf2Iterations = 100_000

// HashPassword returns "pbkdf2-sha256$<iter>$<salt>$<hash>".
func HashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, pbkdf2Iterations, 32)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations, enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

// CheckPassword compares in constant time.
func CheckPassword(stored, password string) bool {
	parts := strings.Split(stored, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[2])
	want, err2 := enc.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, password, salt, iter, len(want))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(got, want) == 1
}

// -------------------------------------------------------------- access tokens

// Claims is what an Access token asserts.
type Claims struct {
	Sub       string `json:"sub"`
	Role      string `json:"role"`
	SubjectID string `json:"subject_id,omitempty"`
	Name      string `json:"name"`
	Exp       int64  `json:"exp"`
}

// TokenCodec signs and verifies HS256 JWTs (T5).
type TokenCodec struct {
	Secret []byte
}

var jwtHeader = base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))

func (c TokenCodec) Sign(cl Claims) (string, error) {
	body, err := json.Marshal(cl)
	if err != nil {
		return "", err
	}
	unsigned := jwtHeader + "." + base64.RawURLEncoding.EncodeToString(body)
	return unsigned + "." + c.mac(unsigned), nil
}

func (c TokenCodec) Verify(token string, now time.Time) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != jwtHeader {
		return Claims{}, ErrInvalidToken
	}
	if !hmac.Equal([]byte(parts[2]), []byte(c.mac(parts[0]+"."+parts[1]))) {
		return Claims{}, ErrInvalidToken
	}
	body, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, ErrInvalidToken
	}
	var cl Claims
	if err := json.Unmarshal(body, &cl); err != nil {
		return Claims{}, ErrInvalidToken
	}
	if now.Unix() >= cl.Exp {
		return Claims{}, ErrInvalidToken
	}
	return cl, nil
}

func (c TokenCodec) mac(s string) string {
	h := hmac.New(sha256.New, c.Secret)
	h.Write([]byte(s))
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}
