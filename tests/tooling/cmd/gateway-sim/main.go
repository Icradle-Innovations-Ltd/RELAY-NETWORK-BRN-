package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	"brn.local/brnproto/relayproto"
)

type gatewayState struct {
	NodeID              string `json:"nodeId"`
	Token               string `json:"token"`
	IdentityPrivatePEM  string `json:"identityPrivatePem"`
	IdentityPublicPEM   string `json:"identityPublicPem"`
	WireGuardPublicKey  string `json:"wireguardPublicKey"`
	WireGuardPrivateKey string `json:"wireguardPrivateKey"`
	FingerprintHash     string `json:"fingerprintHash"`
}

func main() {
	apiBaseURL := flag.String("api", "http://localhost:3000/api", "control-plane API base URL")
	statePath := flag.String("state", ".gateway-sim.json", "path to simulator state file")
	location := flag.String("location", "Kampala", "gateway location")
	flag.Parse()

	state, err := loadOrCreateState(*statePath)
	if err != nil {
		panic(err)
	}
	if state.NodeID == "" || state.Token == "" {
		if err := registerGateway(*apiBaseURL, *location, state); err != nil {
			panic(err)
		}
		if err := saveState(*statePath, state); err != nil {
			panic(err)
		}
	}

	fmt.Printf("gateway simulator node=%s\n", state.NodeID)
	for {
		heartbeat, interval, err := sendHeartbeat(*apiBaseURL, state.Token)
		if err != nil {
			fmt.Println("heartbeat error:", err)
			time.Sleep(5 * time.Second)
			continue
		}
		for _, item := range heartbeat.AssignedSessions {
			go func(sessionID string, relayToken string, endpoint string) {
				if err := announceGatewayPeer(state.NodeID, relayToken, endpoint); err != nil {
					fmt.Println("relay announce error:", err)
				}
			}(item.SessionID, item.RelayToken, item.Relay.UDPEndpoint)
		}
		time.Sleep(time.Duration(interval) * time.Second)
	}
}

type registerRequest struct {
	Type               string         `json:"type"`
	IdentityPublicKey  string         `json:"identityPublicKey"`
	WireguardPublicKey string         `json:"wireguardPublicKey"`
	FingerprintHash    string         `json:"fingerprintHash"`
	Location           string         `json:"location"`
	Capabilities       map[string]any `json:"capabilities"`
	Timestamp          int64          `json:"timestamp"`
	Nonce              string         `json:"nonce"`
	Signature          string         `json:"signature"`
}

type registerResponse struct {
	NodeID string `json:"nodeId"`
	Token  string `json:"token"`
}

type heartbeatResponse struct {
	HeartbeatIntervalSec int `json:"heartbeatIntervalSec"`
	AssignedSessions     []struct {
		SessionID  string `json:"sessionId"`
		RelayToken string `json:"relayToken"`
		Relay      struct {
			UDPEndpoint string `json:"udpEndpoint"`
		} `json:"relay"`
	} `json:"assignedSessions"`
}

func registerGateway(apiBaseURL, location string, state *gatewayState) error {
	request := registerRequest{
		Type:               "GATEWAY",
		IdentityPublicKey:  state.IdentityPublicPEM,
		WireguardPublicKey: state.WireGuardPublicKey,
		FingerprintHash:    state.FingerprintHash,
		Location:           location,
		Capabilities: map[string]any{
			"platform":   "gateway-sim",
			"transports": []string{"udp", "tcp_fallback"},
			"vpnService": false,
		},
		Timestamp: time.Now().UnixMilli(),
		Nonce:     randomBase64(18),
	}
	signature, err := signRegistration(state.IdentityPrivatePEM, request)
	if err != nil {
		return err
	}
	request.Signature = signature

	var response registerResponse
	if err := doJSON(http.MethodPost, strings.TrimRight(apiBaseURL, "/")+"/nodes/register", request, "", &response); err != nil {
		return err
	}
	state.NodeID = response.NodeID
	state.Token = response.Token
	return nil
}

func sendHeartbeat(apiBaseURL, token string) (*heartbeatResponse, int, error) {
	request := map[string]any{
		"status":         "ACTIVE",
		"activeSessions": 0,
		"relayHealthy":   true,
		"networkType":    "SIMULATED",
		"currentPublicIp": nil,
		"loadFactor":     0,
		"appVersion":     "gateway-sim",
	}
	var response heartbeatResponse
	if err := doJSON(http.MethodPost, strings.TrimRight(apiBaseURL, "/")+"/nodes/heartbeat", request, token, &response); err != nil {
		return nil, 0, err
	}
	return &response, response.HeartbeatIntervalSec, nil
}

func announceGatewayPeer(nodeID, relayToken, endpoint string) error {
	relayAddr, err := net.ResolveUDPAddr("udp", endpoint)
	if err != nil {
		return err
	}
	conn, err := net.DialUDP("udp", nil, relayAddr)
	if err != nil {
		return err
	}
	defer conn.Close()

	packet, err := relayproto.EncodeUDPHello(relayproto.Hello{
		Token:  relayToken,
		Role:   "gateway",
		NodeID: nodeID,
	})
	if err != nil {
		return err
	}
	_, err = conn.Write(packet)
	return err
}

func loadOrCreateState(path string) (*gatewayState, error) {
	data, err := os.ReadFile(path)
	if err == nil {
		var state gatewayState
		if unmarshalErr := json.Unmarshal(data, &state); unmarshalErr != nil {
			return nil, unmarshalErr
		}
		return &state, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	publicPEM, privatePEM, err := generateIdentityKeyPair()
	if err != nil {
		return nil, err
	}
	wireguardPrivate, wireguardPublic, err := generateWireGuardKeyPair()
	if err != nil {
		return nil, err
	}
	return &gatewayState{
		IdentityPrivatePEM:  privatePEM,
		IdentityPublicPEM:   publicPEM,
		WireGuardPublicKey:  wireguardPublic,
		WireGuardPrivateKey: wireguardPrivate,
		FingerprintHash:     sha256Hex("gateway-sim|uganda"),
	}, nil
}

func saveState(path string, state *gatewayState) error {
	body, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, body, 0o600)
}

func generateIdentityKeyPair() (publicPEM string, privatePEM string, err error) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return "", "", err
	}
	publicDER, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		return "", "", err
	}
	privateDER, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		return "", "", err
	}
	publicPEM = string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER}))
	privatePEM = string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateDER}))
	return publicPEM, privatePEM, nil
}

func generateWireGuardKeyPair() (privateKey string, publicKey string, err error) {
	curve := ecdh.X25519()
	key, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(key.Bytes()), base64.StdEncoding.EncodeToString(key.PublicKey().Bytes()), nil
}

func signRegistration(privatePEM string, request registerRequest) (string, error) {
	block, _ := pem.Decode([]byte(privatePEM))
	if block == nil {
		return "", errors.New("decode private key pem")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return "", err
	}
	privateKey := key.(ed25519.PrivateKey)
	payload := []byte(strings.Join([]string{
		"BRN_REGISTER_V1",
		request.Type,
		request.IdentityPublicKey,
		request.WireguardPublicKey,
		request.FingerprintHash,
		request.Location,
		stableJSONString(request.Capabilities),
		fmt.Sprintf("%d", request.Timestamp),
		request.Nonce,
	}, "|"))
	signature := ed25519.Sign(privateKey, payload)
	return base64.StdEncoding.EncodeToString(signature), nil
}

func doJSON(method, url string, requestBody any, bearerToken string, responseBody any) error {
	var bodyReader io.Reader
	if requestBody != nil {
		body, err := json.Marshal(requestBody)
		if err != nil {
			return err
		}
		bodyReader = strings.NewReader(string(body))
	}
	req, err := http.NewRequest(method, url, bodyReader)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+bearerToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("api failed: status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return json.Unmarshal(body, responseBody)
}

func stableJSONString(value any) string {
	switch typed := value.(type) {
	case nil:
		return "{}"
	case []string:
		parts := make([]string, 0, len(typed))
		for _, item := range typed {
			parts = append(parts, stableJSONString(item))
		}
		return "[" + strings.Join(parts, ",") + "]"
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		parts := make([]string, 0, len(keys))
		for _, key := range keys {
			parts = append(parts, fmt.Sprintf("%q:%s", key, stableJSONString(typed[key])))
		}
		return "{" + strings.Join(parts, ",") + "}"
	default:
		body, _ := json.Marshal(typed)
		return string(body)
	}
}

func randomBase64(size int) string {
	bytes := make([]byte, size)
	_, _ = rand.Read(bytes)
	return base64.StdEncoding.EncodeToString(bytes)
}

func sha256Hex(value string) string {
	sum := sha256.Sum256([]byte(value))
	return fmt.Sprintf("%x", sum[:])
}
