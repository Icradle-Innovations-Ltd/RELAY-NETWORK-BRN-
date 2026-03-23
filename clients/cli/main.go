package main

import (
	"context"
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
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"brn.local/brnproto/relayproto"
)

type clientState struct {
	APIBaseURL          string `json:"apiBaseUrl"`
	NodeID              string `json:"nodeId"`
	Token               string `json:"token"`
	IdentityPrivatePEM  string `json:"identityPrivatePem"`
	IdentityPublicPEM   string `json:"identityPublicPem"`
	WireGuardPrivateKey string `json:"wireguardPrivateKey"`
	WireGuardPublicKey  string `json:"wireguardPublicKey"`
	FingerprintHash     string `json:"fingerprintHash"`
}

type registerRequest struct {
	Type               string         `json:"type"`
	IdentityPublicKey  string         `json:"identityPublicKey"`
	WireguardPublicKey string         `json:"wireguardPublicKey"`
	FingerprintHash    string         `json:"fingerprintHash"`
	Location           string         `json:"location,omitempty"`
	Capabilities       map[string]any `json:"capabilities,omitempty"`
	Timestamp          int64          `json:"timestamp"`
	Nonce              string         `json:"nonce"`
	Signature          string         `json:"signature"`
}

type registerResponse struct {
	NodeID               string `json:"nodeId"`
	Token                string `json:"token"`
	HeartbeatIntervalSec int    `json:"heartbeatIntervalSec"`
	Relay                struct {
		UDPEndpoint string `json:"udpEndpoint"`
		TCPEndpoint string `json:"tcpEndpoint"`
	} `json:"relay"`
}

type availableGatewaysResponse struct {
	Gateways []struct {
		ID                string         `json:"id"`
		Location          *string        `json:"location"`
		NetworkType       *string        `json:"networkType"`
		CurrentPublicIP   *string        `json:"currentPublicIp"`
		LoadFactor        int            `json:"loadFactor"`
		Capabilities      map[string]any `json:"capabilities"`
		SupportedTransports []string     `json:"supportedTransports"`
		HeartbeatAgeSec   *int           `json:"heartbeatAgeSec"`
	} `json:"gateways"`
}

type sessionStartRequest struct {
	GatewayID           string   `json:"gatewayId"`
	RoutingMode         string   `json:"routingMode"`
	TransportPreference string   `json:"transportPreference"`
	RequestedCIDRs      []string `json:"requestedCidrs,omitempty"`
	RequestedDomains    []string `json:"requestedDomains,omitempty"`
	DataCapMB           int      `json:"dataCapMb,omitempty"`
}

type sessionStartResponse struct {
	SessionID     string `json:"sessionId"`
	RelayToken    string `json:"relayToken"`
	RoutingMode   string `json:"routingMode"`
	TransportMode string `json:"transportMode"`
	DataCapMB     int    `json:"dataCapMb"`
	Relay         struct {
		UDPEndpoint string `json:"udpEndpoint"`
		TCPEndpoint string `json:"tcpEndpoint"`
	} `json:"relay"`
	Tunnel struct {
		ClientTunnelIP string   `json:"clientTunnelIp"`
		GatewayTunnelIP string  `json:"gatewayTunnelIp"`
		NetworkCIDR    string   `json:"networkCidr"`
		DNSServers     []string `json:"dnsServers"`
		KeepaliveSec   int      `json:"keepaliveSec"`
		MTU            int      `json:"mtu"`
	} `json:"tunnel"`
	Peer struct {
		WireGuardPublicKey string `json:"wireguardPublicKey"`
	} `json:"peer"`
	RequestedCIDRs   []string `json:"requestedCidrs"`
	RequestedDomains []string `json:"requestedDomains"`
}

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	switch os.Args[1] {
	case "register":
		must(runRegister(os.Args[2:]))
	case "gateways":
		must(runGateways(os.Args[2:]))
	case "connect":
		must(runConnect(os.Args[2:]))
	default:
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("brn-cli commands:")
	fmt.Println("  register --api http://localhost:3000/api --state .brn-client.json")
	fmt.Println("  gateways --api http://localhost:3000/api")
	fmt.Println("  connect --gateway-id <id> --state .brn-client.json --config-out client.conf")
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func runRegister(args []string) error {
	fs := flag.NewFlagSet("register", flag.ContinueOnError)
	apiBaseURL := fs.String("api", "http://localhost:3000/api", "control-plane API base URL")
	statePath := fs.String("state", ".brn-client.json", "path to client state file")
	location := fs.String("location", "", "human-readable location")
	if err := fs.Parse(args); err != nil {
		return err
	}

	state, err := loadOrCreateState(*statePath, *apiBaseURL)
	if err != nil {
		return err
	}

	host, _ := os.Hostname()
	request := registerRequest{
		Type:               "CLIENT",
		IdentityPublicKey:  state.IdentityPublicPEM,
		WireguardPublicKey: state.WireGuardPublicKey,
		FingerprintHash:    state.FingerprintHash,
		Location:           *location,
		Capabilities: map[string]any{
			"platform":   runtime.GOOS,
			"arch":       runtime.GOARCH,
			"transports": []string{"udp", "tcp_fallback"},
			"hostname":   host,
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
	if err := doJSON(http.MethodPost, strings.TrimRight(*apiBaseURL, "/")+"/nodes/register", request, "", &response); err != nil {
		return err
	}

	state.APIBaseURL = *apiBaseURL
	state.NodeID = response.NodeID
	state.Token = response.Token
	if err := saveState(*statePath, state); err != nil {
		return err
	}

	fmt.Printf("registered node %s\n", state.NodeID)
	fmt.Printf("relay udp: %s\n", response.Relay.UDPEndpoint)
	fmt.Printf("relay tcp: %s\n", response.Relay.TCPEndpoint)
	return nil
}

func runGateways(args []string) error {
	fs := flag.NewFlagSet("gateways", flag.ContinueOnError)
	apiBaseURL := fs.String("api", "http://localhost:3000/api", "control-plane API base URL")
	if err := fs.Parse(args); err != nil {
		return err
	}

	var response availableGatewaysResponse
	if err := doJSON(http.MethodGet, strings.TrimRight(*apiBaseURL, "/")+"/nodes/available", nil, "", &response); err != nil {
		return err
	}

	if len(response.Gateways) == 0 {
		fmt.Println("no healthy gateways available")
		return nil
	}

	for _, gateway := range response.Gateways {
		location := ""
		if gateway.Location != nil {
			location = *gateway.Location
		}
		networkType := ""
		if gateway.NetworkType != nil {
			networkType = *gateway.NetworkType
		}
		fmt.Printf("%s\tlocation=%s\tnetwork=%s\tload=%d\n", gateway.ID, location, networkType, gateway.LoadFactor)
	}
	return nil
}

func runConnect(args []string) error {
	fs := flag.NewFlagSet("connect", flag.ContinueOnError)
	statePath := fs.String("state", ".brn-client.json", "path to client state file")
	gatewayID := fs.String("gateway-id", "", "gateway node ID")
	mode := fs.String("mode", "FULL", "routing mode: FULL or SELECTIVE")
	transport := fs.String("transport", "AUTO", "transport: AUTO, UDP, TCP_FALLBACK")
	configOut := fs.String("config-out", "client.conf", "path to write WireGuard config")
	localPort := fs.Int("local-port", 51821, "local UDP port exposed to WireGuard")
	allowedCIDRs := fs.String("allowed-cidrs", "", "comma-separated CIDRs for selective routing")
	requestedDomains := fs.String("domains", "", "comma-separated domains for future selective routing support")
	dataCapMB := fs.Int("data-cap-mb", 1024, "session quota in MB")
	if err := fs.Parse(args); err != nil {
		return err
	}

	if *gatewayID == "" {
		return errors.New("gateway-id is required")
	}

	state, err := loadState(*statePath)
	if err != nil {
		return err
	}
	if state.Token == "" || state.NodeID == "" {
		return errors.New("client is not registered yet; run register first")
	}

	request := sessionStartRequest{
		GatewayID:           *gatewayID,
		RoutingMode:         strings.ToUpper(*mode),
		TransportPreference: strings.ToUpper(*transport),
		RequestedCIDRs:      splitCSV(*allowedCIDRs),
		RequestedDomains:    splitCSV(*requestedDomains),
		DataCapMB:           *dataCapMB,
	}

	var session sessionStartResponse
	if err := doJSON(http.MethodPost, strings.TrimRight(state.APIBaseURL, "/")+"/sessions/start", request, state.Token, &session); err != nil {
		return err
	}

	if err := writeWireGuardConfig(*configOut, state, session, *localPort); err != nil {
		return err
	}

	fmt.Printf("session started: %s\n", session.SessionID)
	fmt.Printf("wireguard config written to %s\n", *configOut)
	fmt.Printf("bring the tunnel up with endpoint 127.0.0.1:%d, then keep this process running\n", *localPort)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	switch strings.ToUpper(*transport) {
	case "TCP_FALLBACK":
		return bridgeTCP(ctx, *localPort, state.NodeID, session)
	default:
		return bridgeUDP(ctx, *localPort, state.NodeID, session)
	}
}

func loadState(path string) (*clientState, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var state clientState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, err
	}
	return &state, nil
}

func loadOrCreateState(path, apiBaseURL string) (*clientState, error) {
	state, err := loadState(path)
	if err == nil {
		return state, nil
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
	host, _ := os.Hostname()

	return &clientState{
		APIBaseURL:          apiBaseURL,
		IdentityPrivatePEM:  privatePEM,
		IdentityPublicPEM:   publicPEM,
		WireGuardPrivateKey: wireguardPrivate,
		WireGuardPublicKey:  wireguardPublic,
		FingerprintHash:     sha256Hex(host + "|" + runtime.GOOS + "|" + runtime.GOARCH),
	}, nil
}

func saveState(path string, state *clientState) error {
	if err := os.MkdirAll(filepath.Dir(resolvePath(path)), 0o755); err != nil && !errors.Is(err, os.ErrExist) {
		return err
	}
	body, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, body, 0o600)
}

func resolvePath(path string) string {
	if filepath.Dir(path) == "." {
		return filepath.Join(".", path)
	}
	return path
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
	privateKey, ok := key.(ed25519.PrivateKey)
	if !ok {
		return "", errors.New("private key is not ed25519")
	}

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
	if requestBody != nil {
		req.Header.Set("Content-Type", "application/json")
	}
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
		return fmt.Errorf("api %s %s failed: status=%d body=%s", method, url, resp.StatusCode, strings.TrimSpace(string(body)))
	}
	if responseBody == nil {
		return nil
	}
	return json.Unmarshal(body, responseBody)
}

func writeWireGuardConfig(path string, state *clientState, session sessionStartResponse, localPort int) error {
	allowedIPs := []string{"0.0.0.0/0", "::/0"}
	if strings.EqualFold(session.RoutingMode, "SELECTIVE") && len(session.RequestedCIDRs) > 0 {
		allowedIPs = append([]string{}, session.RequestedCIDRs...)
		allowedIPs = append(allowedIPs, strings.Split(session.Tunnel.GatewayTunnelIP, "/")[0]+"/32")
	}

	config := strings.Join([]string{
		"[Interface]",
		"PrivateKey = " + state.WireGuardPrivateKey,
		"Address = " + session.Tunnel.ClientTunnelIP,
		"DNS = " + strings.Join(session.Tunnel.DNSServers, ", "),
		fmt.Sprintf("MTU = %d", session.Tunnel.MTU),
		"",
		"[Peer]",
		"PublicKey = " + session.Peer.WireGuardPublicKey,
		"AllowedIPs = " + strings.Join(allowedIPs, ", "),
		fmt.Sprintf("Endpoint = 127.0.0.1:%d", localPort),
		fmt.Sprintf("PersistentKeepalive = %d", session.Tunnel.KeepaliveSec),
		"",
	}, "\n")

	return os.WriteFile(path, []byte(config), 0o600)
}

func bridgeUDP(ctx context.Context, localPort int, nodeID string, session sessionStartResponse) error {
	localAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		return err
	}
	localConn, err := net.ListenUDP("udp", localAddr)
	if err != nil {
		return err
	}
	defer localConn.Close()

	relayAddr, err := net.ResolveUDPAddr("udp", session.Relay.UDPEndpoint)
	if err != nil {
		return err
	}
	relayConn, err := net.DialUDP("udp", nil, relayAddr)
	if err != nil {
		return err
	}
	defer relayConn.Close()

	helloPacket, err := relayproto.EncodeUDPHello(relayproto.Hello{
		Token:  session.RelayToken,
		Role:   "client",
		NodeID: nodeID,
	})
	if err != nil {
		return err
	}
	if _, err := relayConn.Write(helloPacket); err != nil {
		return err
	}

	var wgAddr *net.UDPAddr
	var wgAddrMu sync.RWMutex
	errCh := make(chan error, 2)

	go func() {
		buffer := make([]byte, 64*1024)
		for {
			_ = localConn.SetReadDeadline(time.Now().Add(2 * time.Second))
			n, addr, err := localConn.ReadFromUDP(buffer)
			if err != nil {
				if isTimeout(err) {
					select {
					case <-ctx.Done():
						errCh <- nil
						return
					default:
						continue
					}
				}
				errCh <- err
				return
			}
			wgAddrMu.Lock()
			copyAddr := *addr
			wgAddr = &copyAddr
			wgAddrMu.Unlock()
			if _, err := relayConn.Write(buffer[:n]); err != nil {
				errCh <- err
				return
			}
		}
	}()

	go func() {
		buffer := make([]byte, 64*1024)
		keepalive := time.NewTicker(20 * time.Second)
		defer keepalive.Stop()

		for {
			select {
			case <-ctx.Done():
				errCh <- nil
				return
			case <-keepalive.C:
				_, _ = relayConn.Write(helloPacket)
			default:
			}

			_ = relayConn.SetReadDeadline(time.Now().Add(2 * time.Second))
			n, err := relayConn.Read(buffer)
			if err != nil {
				if isTimeout(err) {
					continue
				}
				errCh <- err
				return
			}
			wgAddrMu.RLock()
			target := wgAddr
			wgAddrMu.RUnlock()
			if target == nil {
				continue
			}
			if _, err := localConn.WriteToUDP(buffer[:n], target); err != nil {
				errCh <- err
				return
			}
		}
	}()

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func bridgeTCP(ctx context.Context, localPort int, nodeID string, session sessionStartResponse) error {
	localAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		return err
	}
	localConn, err := net.ListenUDP("udp", localAddr)
	if err != nil {
		return err
	}
	defer localConn.Close()

	tcpConn, err := net.Dial("tcp", session.Relay.TCPEndpoint)
	if err != nil {
		return err
	}
	defer tcpConn.Close()

	helloPayload, err := json.Marshal(relayproto.Hello{
		Token:  session.RelayToken,
		Role:   "client",
		NodeID: nodeID,
	})
	if err != nil {
		return err
	}
	if err := relayproto.WriteFrame(tcpConn, relayproto.FrameHello, helloPayload); err != nil {
		return err
	}

	var wgAddr *net.UDPAddr
	var wgAddrMu sync.RWMutex
	errCh := make(chan error, 2)

	go func() {
		buffer := make([]byte, 64*1024)
		for {
			_ = localConn.SetReadDeadline(time.Now().Add(2 * time.Second))
			n, addr, err := localConn.ReadFromUDP(buffer)
			if err != nil {
				if isTimeout(err) {
					select {
					case <-ctx.Done():
						errCh <- nil
						return
					default:
						continue
					}
				}
				errCh <- err
				return
			}
			wgAddrMu.Lock()
			copyAddr := *addr
			wgAddr = &copyAddr
			wgAddrMu.Unlock()
			if err := relayproto.WriteFrame(tcpConn, relayproto.FrameData, buffer[:n]); err != nil {
				errCh <- err
				return
			}
		}
	}()

	go func() {
		keepalive := time.NewTicker(15 * time.Second)
		defer keepalive.Stop()
		for {
			select {
			case <-ctx.Done():
				errCh <- nil
				return
			case <-keepalive.C:
				if err := relayproto.WriteFrame(tcpConn, relayproto.FramePing, nil); err != nil {
					errCh <- err
					return
				}
			default:
			}

			_ = tcpConn.SetReadDeadline(time.Now().Add(2 * time.Second))
			frameType, payload, err := relayproto.ReadFrame(tcpConn)
			if err != nil {
				if isTimeout(err) {
					continue
				}
				errCh <- err
				return
			}
			if frameType != relayproto.FrameData {
				continue
			}
			wgAddrMu.RLock()
			target := wgAddr
			wgAddrMu.RUnlock()
			if target == nil {
				continue
			}
			if _, err := localConn.WriteToUDP(payload, target); err != nil {
				errCh <- err
				return
			}
		}
	}()

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func stableJSONString(value any) string {
	switch typed := value.(type) {
	case nil:
		return "{}"
	case string:
		body, _ := json.Marshal(typed)
		return string(body)
	case []string:
		parts := make([]string, 0, len(typed))
		for _, item := range typed {
			parts = append(parts, stableJSONString(item))
		}
		return "[" + strings.Join(parts, ",") + "]"
	case []any:
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

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	raw := strings.Split(value, ",")
	parts := make([]string, 0, len(raw))
	for _, item := range raw {
		trimmed := strings.TrimSpace(item)
		if trimmed != "" {
			parts = append(parts, trimmed)
		}
	}
	return parts
}

func isTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}
