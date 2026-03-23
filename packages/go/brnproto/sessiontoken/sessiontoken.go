package sessiontoken

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

type Claims struct {
	SessionID                string `json:"sid"`
	ClientID                 string `json:"cid"`
	GatewayID                string `json:"gid"`
	Role                     string `json:"role"`
	ClientTunnelIP           string `json:"clientTunnelIp"`
	GatewayTunnelIP          string `json:"gatewayTunnelIp"`
	ClientWireGuardPublicKey string `json:"clientWireguardPublicKey"`
	GatewayWireGuardPublicKey string `json:"gatewayWireguardPublicKey"`
	QuotaMB                  int    `json:"quotaMb"`
	RoutingMode              string `json:"routingMode"`
	TransportMode            string `json:"transportMode"`
	ExpiresAt                int64  `json:"exp"`
	IssuedAt                 int64  `json:"iat"`
}

func SignHS256(claims Claims, secret []byte) (string, error) {
	headerBytes, err := json.Marshal(map[string]string{
		"alg": "HS256",
		"typ": "JWT",
	})
	if err != nil {
		return "", fmt.Errorf("marshal header: %w", err)
	}

	payloadBytes, err := json.Marshal(claims)
	if err != nil {
		return "", fmt.Errorf("marshal claims: %w", err)
	}

	header := base64.RawURLEncoding.EncodeToString(headerBytes)
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)
	signingInput := header + "." + payload
	mac := hmac.New(sha256.New, secret)
	if _, err := mac.Write([]byte(signingInput)); err != nil {
		return "", fmt.Errorf("sign token: %w", err)
	}

	signature := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return signingInput + "." + signature, nil
}

func VerifyHS256(token string, secret []byte, now time.Time) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Claims{}, errors.New("malformed token")
	}

	signingInput := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, secret)
	if _, err := mac.Write([]byte(signingInput)); err != nil {
		return Claims{}, fmt.Errorf("signing input: %w", err)
	}
	expected := mac.Sum(nil)
	actual, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return Claims{}, fmt.Errorf("decode signature: %w", err)
	}
	if !hmac.Equal(expected, actual) {
		return Claims{}, errors.New("invalid signature")
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, fmt.Errorf("decode payload: %w", err)
	}

	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return Claims{}, fmt.Errorf("unmarshal claims: %w", err)
	}
	if claims.ExpiresAt > 0 && now.Unix() > claims.ExpiresAt {
		return Claims{}, errors.New("token expired")
	}

	return claims, nil
}
