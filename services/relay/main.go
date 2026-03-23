package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"brn.local/brnproto/relayproto"
	"brn.local/brnproto/sessiontoken"
)

type config struct {
	BindUDP          string
	BindTCP          string
	MetricsAddr      string
	SessionSecret    []byte
	UsageSecret      []byte
	UsageURL         string
	IdleTimeout      time.Duration
	TLSCertFile      string
	TLSKeyFile       string
	HandshakeCap     int
	HandshakeRefill  float64
}

type limiter struct {
	mu           sync.Mutex
	tokens       float64
	lastRefillAt time.Time
}

type peerRole string

const (
	roleClient  peerRole = "client"
	roleGateway peerRole = "gateway"
)

type peerBinding struct {
	sessionID string
	role      peerRole
}

type peerState struct {
	nodeID   string
	role     peerRole
	lastSeen time.Time
	udpAddr  *net.UDPAddr
	tcpConn  net.Conn
	send     chan []byte
}

type sessionState struct {
	id          string
	clientID    string
	gatewayID   string
	quotaBytes  int64
	expiresAt   time.Time
	createdAt   time.Time
	lastSeenAt  time.Time
	clientBytes int64
	gatewayBytes int64
	uploadOnce  sync.Once
	client      *peerState
	gateway     *peerState
	mu          sync.RWMutex
}

type server struct {
	cfg            config
	sessionMu      sync.RWMutex
	sessions       map[string]*sessionState
	udpPeerIndex   map[string]peerBinding
	limiters       map[string]*limiter
	limiterMu      sync.Mutex
	activeTCP      atomic.Int64
	udpPackets     atomic.Int64
	usageUploads   atomic.Int64
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("load config: %v", err)
	}

	srv := &server{
		cfg:          cfg,
		sessions:     make(map[string]*sessionState),
		udpPeerIndex: make(map[string]peerBinding),
		limiters:     make(map[string]*limiter),
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go srv.startHTTP()
	go srv.cleanupLoop(ctx)

	go func() {
		if err := srv.serveUDP(ctx); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Fatalf("udp relay failed: %v", err)
		}
	}()

	if err := srv.serveTCP(ctx); err != nil && !errors.Is(err, net.ErrClosed) {
		log.Fatalf("tcp relay failed: %v", err)
	}
}

func loadConfig() (config, error) {
	idleSeconds := 90
	if value := os.Getenv("BRN_RELAY_IDLE_TIMEOUT_SEC"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil {
			return config{}, fmt.Errorf("parse BRN_RELAY_IDLE_TIMEOUT_SEC: %w", err)
		}
		idleSeconds = parsed
	}

	cfg := config{
		BindUDP:         envOrDefault("BRN_RELAY_BIND_UDP", ":51820"),
		BindTCP:         envOrDefault("BRN_RELAY_BIND_TCP", ":8443"),
		MetricsAddr:     envOrDefault("BRN_RELAY_METRICS_ADDR", ":9090"),
		SessionSecret:   []byte(os.Getenv("BRN_SESSION_SECRET")),
		UsageSecret:     []byte(os.Getenv("BRN_RELAY_USAGE_SECRET")),
		UsageURL:        os.Getenv("BRN_CONTROL_PLANE_USAGE_URL"),
		IdleTimeout:     time.Duration(idleSeconds) * time.Second,
		TLSCertFile:     os.Getenv("BRN_RELAY_TLS_CERT_FILE"),
		TLSKeyFile:      os.Getenv("BRN_RELAY_TLS_KEY_FILE"),
		HandshakeCap:    30,
		HandshakeRefill: 1.0,
	}
	if len(cfg.SessionSecret) == 0 {
		return config{}, errors.New("BRN_SESSION_SECRET is required")
	}
	return cfg, nil
}

func envOrDefault(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func (s *server) serveUDP(ctx context.Context) error {
	addr, err := net.ResolveUDPAddr("udp", s.cfg.BindUDP)
	if err != nil {
		return fmt.Errorf("resolve udp addr: %w", err)
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("listen udp: %w", err)
	}
	defer conn.Close()

	log.Printf("udp relay listening on %s", s.cfg.BindUDP)

	buffer := make([]byte, 64*1024)
	for {
		select {
		case <-ctx.Done():
			return nil
		default:
		}

		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, remote, err := conn.ReadFromUDP(buffer)
		if err != nil {
			if isTimeout(err) {
				continue
			}
			return err
		}

		payload := append([]byte(nil), buffer[:n]...)
		if hello, err := relayproto.DecodeUDPHello(payload); err == nil {
			if !s.allow(remote.IP.String()) {
				continue
			}
			if err := s.registerUDPPeer(hello, remote); err != nil {
				log.Printf("reject udp hello from %s: %v", remote, err)
			}
			continue
		}

		s.udpPackets.Add(1)
		if err := s.forwardUDPPacket(conn, remote, payload); err != nil && !errors.Is(err, errPeerUnavailable) {
			log.Printf("udp forward error: %v", err)
		}
	}
}

func (s *server) serveTCP(ctx context.Context) error {
	var listener net.Listener
	var err error
	if s.cfg.TLSCertFile != "" && s.cfg.TLSKeyFile != "" {
		cert, certErr := tls.LoadX509KeyPair(s.cfg.TLSCertFile, s.cfg.TLSKeyFile)
		if certErr != nil {
			return fmt.Errorf("load relay tls cert: %w", certErr)
		}
		listener, err = tls.Listen("tcp", s.cfg.BindTCP, &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		})
	} else {
		listener, err = net.Listen("tcp", s.cfg.BindTCP)
	}
	if err != nil {
		return fmt.Errorf("listen tcp: %w", err)
	}
	defer listener.Close()

	log.Printf("tcp relay listening on %s", s.cfg.BindTCP)

	for {
		select {
		case <-ctx.Done():
			return nil
		default:
		}

		if tcpListener, ok := listener.(*net.TCPListener); ok {
			_ = tcpListener.SetDeadline(time.Now().Add(2 * time.Second))
		}
		conn, err := listener.Accept()
		if err != nil {
			if isTimeout(err) {
				continue
			}
			return err
		}
		s.activeTCP.Add(1)
		go s.handleTCPConn(conn)
	}
}

func (s *server) handleTCPConn(conn net.Conn) {
	defer func() {
		s.activeTCP.Add(-1)
		_ = conn.Close()
	}()

	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	frameType, payload, err := relayproto.ReadFrame(conn)
	if err != nil {
		return
	}
	if frameType != relayproto.FrameHello {
		return
	}

	var hello relayproto.Hello
	if err := json.Unmarshal(payload, &hello); err != nil {
		return
	}

	remoteHost, _, _ := net.SplitHostPort(conn.RemoteAddr().String())
	if !s.allow(remoteHost) {
		return
	}

	session, peer, err := s.registerTCPPeer(hello, conn)
	if err != nil {
		log.Printf("reject tcp hello from %s: %v", conn.RemoteAddr(), err)
		return
	}

	_ = conn.SetDeadline(time.Time{})
	go s.tcpWriteLoop(conn, peer)

	for {
		frameType, payload, err = relayproto.ReadFrame(conn)
		if err != nil {
			if err != io.EOF {
				log.Printf("tcp read error: %v", err)
			}
			s.detachTCPPeer(session, peer.role)
			return
		}

		switch frameType {
		case relayproto.FramePing:
			peer.lastSeen = time.Now()
			_ = relayproto.WriteFrame(conn, relayproto.FramePong, nil)
		case relayproto.FrameData:
			peer.lastSeen = time.Now()
			if err := s.forwardTCPPayload(session.id, peer.role, payload); err != nil && !errors.Is(err, errPeerUnavailable) {
				log.Printf("tcp forward error: %v", err)
			}
		}
	}
}

func (s *server) tcpWriteLoop(conn net.Conn, peer *peerState) {
	for payload := range peer.send {
		if err := relayproto.WriteFrame(conn, relayproto.FrameData, payload); err != nil {
			return
		}
	}
}

func (s *server) allow(source string) bool {
	s.limiterMu.Lock()
	defer s.limiterMu.Unlock()

	limit := s.limiters[source]
	if limit == nil {
		limit = &limiter{
			tokens:       float64(s.cfg.HandshakeCap),
			lastRefillAt: time.Now(),
		}
		s.limiters[source] = limit
	}

	limit.mu.Lock()
	defer limit.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(limit.lastRefillAt).Seconds()
	limit.tokens += elapsed * s.cfg.HandshakeRefill
	if limit.tokens > float64(s.cfg.HandshakeCap) {
		limit.tokens = float64(s.cfg.HandshakeCap)
	}
	limit.lastRefillAt = now
	if limit.tokens < 1 {
		return false
	}
	limit.tokens -= 1
	return true
}

func (s *server) registerUDPPeer(hello relayproto.Hello, addr *net.UDPAddr) error {
	session, peer, err := s.validateAndAttach(hello)
	if err != nil {
		return err
	}

	session.mu.Lock()
	oldAddr := ""
	if peer.udpAddr != nil {
		oldAddr = peer.udpAddr.String()
	}
	copyAddr := *addr
	peer.udpAddr = &copyAddr
	peer.lastSeen = time.Now()
	session.mu.Unlock()

	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()
	if oldAddr != "" {
		delete(s.udpPeerIndex, oldAddr)
	}
	s.udpPeerIndex[addr.String()] = peerBinding{
		sessionID: session.id,
		role:      peer.role,
	}
	return nil
}

func (s *server) registerTCPPeer(hello relayproto.Hello, conn net.Conn) (*sessionState, *peerState, error) {
	session, peer, err := s.validateAndAttach(hello)
	if err != nil {
		return nil, nil, err
	}

	session.mu.Lock()
	defer session.mu.Unlock()

	if peer.tcpConn != nil {
		_ = peer.tcpConn.Close()
	}
	if peer.send != nil {
		close(peer.send)
	}
	peer.tcpConn = conn
	peer.send = make(chan []byte, 256)
	peer.lastSeen = time.Now()
	return session, peer, nil
}

func (s *server) validateAndAttach(hello relayproto.Hello) (*sessionState, *peerState, error) {
	claims, err := sessiontoken.VerifyHS256(hello.Token, s.cfg.SessionSecret, time.Now())
	if err != nil {
		return nil, nil, fmt.Errorf("verify token: %w", err)
	}

	role := peerRole(strings.ToLower(hello.Role))
	if role != roleClient && role != roleGateway {
		return nil, nil, errors.New("invalid role")
	}
	if claims.Role != string(role) {
		return nil, nil, errors.New("role mismatch")
	}
	if hello.NodeID == "" {
		return nil, nil, errors.New("missing node id")
	}
	if role == roleClient && hello.NodeID != claims.ClientID {
		return nil, nil, errors.New("client node mismatch")
	}
	if role == roleGateway && hello.NodeID != claims.GatewayID {
		return nil, nil, errors.New("gateway node mismatch")
	}

	session := s.getOrCreateSession(claims)
	session.mu.Lock()
	defer session.mu.Unlock()
	session.lastSeenAt = time.Now()

	var peer *peerState
	if role == roleClient {
		if session.client == nil {
			session.client = &peerState{nodeID: claims.ClientID, role: roleClient}
		}
		peer = session.client
	} else {
		if session.gateway == nil {
			session.gateway = &peerState{nodeID: claims.GatewayID, role: roleGateway}
		}
		peer = session.gateway
	}
	return session, peer, nil
}

func (s *server) getOrCreateSession(claims sessiontoken.Claims) *sessionState {
	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()

	if session, ok := s.sessions[claims.SessionID]; ok {
		return session
	}

	session := &sessionState{
		id:         claims.SessionID,
		clientID:   claims.ClientID,
		gatewayID:  claims.GatewayID,
		quotaBytes: int64(claims.QuotaMB) * 1024 * 1024,
		expiresAt:  time.Unix(claims.ExpiresAt, 0),
		createdAt:  time.Now(),
		lastSeenAt: time.Now(),
	}
	s.sessions[claims.SessionID] = session
	return session
}

var errPeerUnavailable = errors.New("peer unavailable")

func (s *server) forwardUDPPacket(conn *net.UDPConn, source *net.UDPAddr, payload []byte) error {
	s.sessionMu.RLock()
	binding, ok := s.udpPeerIndex[source.String()]
	s.sessionMu.RUnlock()
	if !ok {
		return errPeerUnavailable
	}

	session, ok := s.lookupSession(binding.sessionID)
	if !ok {
		return errPeerUnavailable
	}

	targetAddr, err := s.recordForward(session, binding.role, len(payload))
	if err != nil {
		return err
	}
	if targetAddr == nil {
		return errPeerUnavailable
	}

	_, err = conn.WriteToUDP(payload, targetAddr)
	return err
}

func (s *server) forwardTCPPayload(sessionID string, sourceRole peerRole, payload []byte) error {
	session, ok := s.lookupSession(sessionID)
	if !ok {
		return errPeerUnavailable
	}

	_, err := s.recordForward(session, sourceRole, len(payload))
	if err != nil {
		return err
	}

	session.mu.RLock()
	defer session.mu.RUnlock()

	var target *peerState
	if sourceRole == roleClient {
		target = session.gateway
	} else {
		target = session.client
	}
	if target == nil || target.send == nil {
		return errPeerUnavailable
	}

	select {
	case target.send <- append([]byte(nil), payload...):
		return nil
	default:
		return errors.New("target tcp buffer full")
	}
}

func (s *server) recordForward(session *sessionState, sourceRole peerRole, size int) (*net.UDPAddr, error) {
	session.mu.Lock()
	defer session.mu.Unlock()

	now := time.Now()
	if !session.expiresAt.IsZero() && now.After(session.expiresAt) {
		go s.endSession(session, "expired")
		return nil, errors.New("session expired")
	}
	session.lastSeenAt = now

	if sourceRole == roleClient {
		session.clientBytes += int64(size)
	} else {
		session.gatewayBytes += int64(size)
	}

	if session.quotaBytes > 0 && session.clientBytes+session.gatewayBytes > session.quotaBytes {
		go s.endSession(session, "quota_exceeded")
		return nil, errors.New("quota exceeded")
	}

	if sourceRole == roleClient {
		if session.gateway == nil || session.gateway.udpAddr == nil {
			return nil, nil
		}
		addr := *session.gateway.udpAddr
		return &addr, nil
	}
	if session.client == nil || session.client.udpAddr == nil {
		return nil, nil
	}
	addr := *session.client.udpAddr
	return &addr, nil
}

func (s *server) lookupSession(sessionID string) (*sessionState, bool) {
	s.sessionMu.RLock()
	defer s.sessionMu.RUnlock()
	session, ok := s.sessions[sessionID]
	return session, ok
}

func (s *server) detachTCPPeer(session *sessionState, role peerRole) {
	session.mu.Lock()
	defer session.mu.Unlock()
	var peer *peerState
	if role == roleClient {
		peer = session.client
	} else {
		peer = session.gateway
	}
	if peer == nil {
		return
	}
	if peer.send != nil {
		close(peer.send)
		peer.send = nil
	}
	peer.tcpConn = nil
	peer.lastSeen = time.Now()
}

func (s *server) cleanupLoop(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			sweepBefore := time.Now().Add(-s.cfg.IdleTimeout)
			var expired []*sessionState

			s.sessionMu.RLock()
			for _, session := range s.sessions {
				session.mu.RLock()
				stale := session.lastSeenAt.Before(sweepBefore)
				expiredByTime := !session.expiresAt.IsZero() && time.Now().After(session.expiresAt)
				session.mu.RUnlock()
				if stale || expiredByTime {
					expired = append(expired, session)
				}
			}
			s.sessionMu.RUnlock()

			for _, session := range expired {
				reason := "idle_timeout"
				if !session.expiresAt.IsZero() && time.Now().After(session.expiresAt) {
					reason = "expired"
				}
				s.endSession(session, reason)
			}
		}
	}
}

func (s *server) endSession(session *sessionState, reason string) {
	session.uploadOnce.Do(func() {
		s.sessionMu.Lock()
		delete(s.sessions, session.id)
		for addr, binding := range s.udpPeerIndex {
			if binding.sessionID == session.id {
				delete(s.udpPeerIndex, addr)
			}
		}
		s.sessionMu.Unlock()

		session.mu.Lock()
		if session.client != nil && session.client.tcpConn != nil {
			_ = session.client.tcpConn.Close()
		}
		if session.client != nil && session.client.send != nil {
			close(session.client.send)
		}
		if session.gateway != nil && session.gateway.tcpConn != nil {
			_ = session.gateway.tcpConn.Close()
		}
		if session.gateway != nil && session.gateway.send != nil {
			close(session.gateway.send)
		}
		clientBytes := session.clientBytes
		gatewayBytes := session.gatewayBytes
		session.mu.Unlock()

		go s.uploadUsage(session.id, session.clientID, session.gatewayID, clientBytes, gatewayBytes, reason)
	})
}

func (s *server) uploadUsage(sessionID, clientID, gatewayID string, clientBytes, gatewayBytes int64, reason string) {
	if s.cfg.UsageURL == "" || len(s.cfg.UsageSecret) == 0 {
		return
	}

	payload := map[string]any{
		"sessionId":         sessionID,
		"gatewayId":         gatewayID,
		"clientId":          clientID,
		"bytesUp":           clientBytes,
		"bytesDown":         gatewayBytes,
		"transportMode":     "AUTO",
		"durationSec":       0,
		"terminationReason": reason,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return
	}

	mac := hmac.New(sha256.New, s.cfg.UsageSecret)
	_, _ = mac.Write(body)
	signature := hex.EncodeToString(mac.Sum(nil))

	req, err := http.NewRequest(http.MethodPost, s.cfg.UsageURL, strings.NewReader(string(body)))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-BRN-Relay-Signature", signature)

	resp, err := http.DefaultClient.Do(req)
	if err == nil && resp != nil {
		_ = resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			s.usageUploads.Add(1)
		}
	}
}

func (s *server) startHTTP() {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok": true,
		})
	})
	mux.HandleFunc("/metrics", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4")
		fmt.Fprintf(w, "brn_sessions_active %d\n", s.sessionCount())
		fmt.Fprintf(w, "brn_udp_packets_total %d\n", s.udpPackets.Load())
		fmt.Fprintf(w, "brn_tcp_connections_active %d\n", s.activeTCP.Load())
		fmt.Fprintf(w, "brn_usage_uploads_total %d\n", s.usageUploads.Load())
	})

	log.Printf("relay metrics listening on %s", s.cfg.MetricsAddr)
	if err := http.ListenAndServe(s.cfg.MetricsAddr, mux); err != nil {
		log.Printf("metrics server stopped: %v", err)
	}
}

func (s *server) sessionCount() int {
	s.sessionMu.RLock()
	defer s.sessionMu.RUnlock()
	return len(s.sessions)
}

func isTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}
