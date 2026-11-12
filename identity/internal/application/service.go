// Package application holds Identity's use cases: log in, verify a token.
package application

import (
	"context"
	"errors"
	"time"

	"clinic/identity/internal/domain"
	"clinic/internal/platform/clock"
)

type (
	User   = domain.User
	Claims = domain.Claims
)

var (
	ErrInvalidCredentials = domain.ErrInvalidCredentials
	ErrInvalidToken       = domain.ErrInvalidToken
)

type Service struct {
	users domain.Users
	codec domain.TokenCodec
	clock clock.Clock
	ttl   time.Duration
}

func NewService(users domain.Users, secret []byte, c clock.Clock, ttl time.Duration) *Service {
	return &Service{users: users, codec: domain.TokenCodec{Secret: secret}, clock: c, ttl: ttl}
}

// Login checks a Credential and issues an Access token.
func (s *Service) Login(ctx context.Context, username, password string) (string, Claims, error) {
	u, err := s.users.ByUsername(ctx, username)
	if errors.Is(err, domain.ErrNotFound) {
		domain.CheckPassword("pbkdf2-sha256$1$AA$AA", password) // keep timing similar
		return "", Claims{}, ErrInvalidCredentials
	}
	if err != nil {
		return "", Claims{}, err
	}
	if !domain.CheckPassword(u.PasswordHash, password) {
		return "", Claims{}, ErrInvalidCredentials
	}
	cl := Claims{Sub: u.ID, Role: u.Role, SubjectID: u.SubjectID, Name: u.DisplayName,
		Exp: s.clock.Now().Add(s.ttl).Unix()}
	tok, err := s.codec.Sign(cl)
	return tok, cl, err
}

// Verify checks signature and expiry. Expiry uses real time, not the demo clock,
// so moving the demo clock forward does not log everybody out.
func (s *Service) Verify(token string) (Claims, error) {
	return s.codec.Verify(token, time.Now())
}

// Users lists users without their hashes (demo user switcher).
func (s *Service) Users(ctx context.Context) ([]User, error) {
	us, err := s.users.List(ctx)
	for i := range us {
		us[i].PasswordHash = ""
	}
	return us, err
}
