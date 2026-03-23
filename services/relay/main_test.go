package main

import (
	"testing"
	"time"

	"brn.local/brnproto/sessiontoken"
)

func TestGetOrCreateSession(t *testing.T) {
	srv := &server{
		cfg:      config{SessionSecret: []byte("secret")},
		sessions: make(map[string]*sessionState),
	}

	claims := sessiontoken.Claims{
		SessionID: "session-1",
		ClientID:  "client-1",
		GatewayID: "gateway-1",
		QuotaMB:   64,
		ExpiresAt: time.Now().Add(5 * time.Minute).Unix(),
	}

	first := srv.getOrCreateSession(claims)
	second := srv.getOrCreateSession(claims)
	if first != second {
		t.Fatalf("expected same session pointer")
	}
	if first.quotaBytes != 64*1024*1024 {
		t.Fatalf("unexpected quota bytes: %d", first.quotaBytes)
	}
}
