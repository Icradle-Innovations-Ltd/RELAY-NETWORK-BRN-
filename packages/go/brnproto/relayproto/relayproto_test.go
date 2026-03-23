package relayproto

import (
	"bytes"
	"testing"
)

func TestUDPHelloRoundTrip(t *testing.T) {
	packet, err := EncodeUDPHello(Hello{
		Token:  "relay-token",
		Role:   "client",
		NodeID: "node-123",
	})
	if err != nil {
		t.Fatalf("encode failed: %v", err)
	}

	hello, err := DecodeUDPHello(packet)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if hello.Role != "client" || hello.NodeID != "node-123" {
		t.Fatalf("unexpected hello payload: %+v", hello)
	}
}

func TestFrameRoundTrip(t *testing.T) {
	var buffer bytes.Buffer
	payload := []byte("wireguard-packet")
	if err := WriteFrame(&buffer, FrameData, payload); err != nil {
		t.Fatalf("write frame failed: %v", err)
	}

	frameType, decoded, err := ReadFrame(&buffer)
	if err != nil {
		t.Fatalf("read frame failed: %v", err)
	}
	if frameType != FrameData {
		t.Fatalf("unexpected frame type: got %d want %d", frameType, FrameData)
	}
	if string(decoded) != string(payload) {
		t.Fatalf("unexpected payload: got %q want %q", string(decoded), string(payload))
	}
}
