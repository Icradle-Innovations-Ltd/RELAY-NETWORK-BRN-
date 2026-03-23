package relayproto

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

const (
	UDPHelloType byte = 0x01
	FrameHello   byte = 0x01
	FrameData    byte = 0x02
	FramePing    byte = 0x03
	FramePong    byte = 0x04
	MaxFrameSize      = 1024 * 1024
)

var (
	magic      = []byte("BRN1")
	ErrNotHello = errors.New("not a udp hello packet")
)

type Hello struct {
	Token  string `json:"token"`
	Role   string `json:"role"`
	NodeID string `json:"nodeId"`
}

func EncodeUDPHello(hello Hello) ([]byte, error) {
	payload, err := json.Marshal(hello)
	if err != nil {
		return nil, fmt.Errorf("marshal hello: %w", err)
	}

	packet := make([]byte, 0, len(magic)+1+len(payload))
	packet = append(packet, magic...)
	packet = append(packet, UDPHelloType)
	packet = append(packet, payload...)
	return packet, nil
}

func DecodeUDPHello(packet []byte) (Hello, error) {
	if len(packet) < len(magic)+1 {
		return Hello{}, ErrNotHello
	}
	if string(packet[:len(magic)]) != string(magic) || packet[len(magic)] != UDPHelloType {
		return Hello{}, ErrNotHello
	}

	var hello Hello
	if err := json.Unmarshal(packet[len(magic)+1:], &hello); err != nil {
		return Hello{}, fmt.Errorf("unmarshal hello: %w", err)
	}
	return hello, nil
}

func WriteFrame(w io.Writer, frameType byte, payload []byte) error {
	if len(payload) > MaxFrameSize {
		return fmt.Errorf("frame too large: %d", len(payload))
	}

	header := make([]byte, 5)
	header[0] = frameType
	binary.BigEndian.PutUint32(header[1:], uint32(len(payload)))
	if _, err := w.Write(header); err != nil {
		return err
	}
	if len(payload) == 0 {
		return nil
	}
	_, err := w.Write(payload)
	return err
}

func ReadFrame(r io.Reader) (byte, []byte, error) {
	header := make([]byte, 5)
	if _, err := io.ReadFull(r, header); err != nil {
		return 0, nil, err
	}
	frameType := header[0]
	length := binary.BigEndian.Uint32(header[1:])
	if length > MaxFrameSize {
		return 0, nil, fmt.Errorf("frame too large: %d", length)
	}

	payload := make([]byte, length)
	if length > 0 {
		if _, err := io.ReadFull(r, payload); err != nil {
			return 0, nil, err
		}
	}
	return frameType, payload, nil
}
