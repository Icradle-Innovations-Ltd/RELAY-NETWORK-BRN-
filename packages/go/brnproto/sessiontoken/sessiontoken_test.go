package sessiontoken

import (
	"testing"
	"time"
)

func TestSignAndVerifyHS256(t *testing.T) {
	claims := Claims{
		SessionID:                "session-123",
		ClientID:                 "client-123",
		GatewayID:                "gateway-123",
		Role:                     "client",
		ClientTunnelIP:           "100.64.0.2/32",
		GatewayTunnelIP:          "100.64.0.3/32",
		ClientWireGuardPublicKey: "client-public",
		GatewayWireGuardPublicKey: "gateway-public",
		QuotaMB:                  128,
		RoutingMode:              "FULL",
		TransportMode:            "AUTO",
		IssuedAt:                 time.Now().Unix(),
		ExpiresAt:                time.Now().Add(5 * time.Minute).Unix(),
	}

	token, err := SignHS256(claims, []byte("test-secret"))
	if err != nil {
		t.Fatalf("sign failed: %v", err)
	}

	verified, err := VerifyHS256(token, []byte("test-secret"), time.Now())
	if err != nil {
		t.Fatalf("verify failed: %v", err)
	}
	if verified.SessionID != claims.SessionID {
		t.Fatalf("unexpected session id: got %q want %q", verified.SessionID, claims.SessionID)
	}
	if verified.Role != claims.Role {
		t.Fatalf("unexpected role: got %q want %q", verified.Role, claims.Role)
	}
}

func TestVerifyRejectsExpiredToken(t *testing.T) {
	claims := Claims{
		SessionID: "expired-session",
		ExpiresAt: time.Now().Add(-1 * time.Minute).Unix(),
	}
	token, err := SignHS256(claims, []byte("test-secret"))
	if err != nil {
		t.Fatalf("sign failed: %v", err)
	}

	if _, err := VerifyHS256(token, []byte("test-secret"), time.Now()); err == nil {
		t.Fatal("expected expired token to be rejected")
	}
}
