# BRN Relay Protocol

## Session token

The control plane issues an HS256 JWT signed with `BRN_SESSION_SECRET`.

Required claims:

- `sid`: session ID
- `cid`: client node ID
- `gid`: gateway node ID
- `role`: `client` or `gateway` when issued to a specific peer
- `exp`: Unix expiry timestamp
- `quotaMb`: per-session quota
- `clientTunnelIp`: `/32` address for the client WireGuard peer
- `gatewayTunnelIp`: `/32` address for the gateway WireGuard peer
- `clientWireguardPublicKey`
- `gatewayWireguardPublicKey`
- `transportMode`
- `routingMode`

## UDP

### Handshake

The first UDP datagram from each peer must be:

```text
magic: "BRN1" (4 bytes)
type: 0x01 (1 byte)
json payload:
{
  "token": "<relay-session-token>",
  "role": "client" | "gateway",
  "nodeId": "<node-id>"
}
```

After the relay validates the token and associates the sender address with the session, all subsequent datagrams from that source address are treated as opaque encrypted payload bytes and forwarded to the opposite peer unchanged.

## TCP fallback

Every TCP connection starts with a framed `HELLO` message and then exchanges framed `DATA`, `PING`, and `PONG` messages.

Frame format:

```text
type: uint8
length: uint32 big-endian
payload: []byte
```

Frame types:

- `0x01`: `HELLO` JSON payload
- `0x02`: `DATA` raw encrypted packet payload
- `0x03`: `PING`
- `0x04`: `PONG`

## Usage upload

The relay sends JSON to `POST /billing/usage` with `x-brn-relay-signature` equal to `hex(HMAC-SHA256(body, BRN_RELAY_USAGE_SECRET))`.
