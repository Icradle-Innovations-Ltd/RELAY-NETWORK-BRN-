# BRN Architecture

## Core path

```text
                               +-----------------------+
                               |   Control Plane API   |
                               | Next.js + Prisma + PG |
                               +-----------+-----------+
                                           ^
                      register / heartbeat | session start / usage ingest
                                           |
         +-------------------+             |             +------------------+
         |   Gateway Node    |-------------+-------------|    Client CLI    |
         | Android VpnService|                           | Local UDP bridge |
         +---------+---------+                           +---------+--------+
                   \                                             /
                    \   encrypted WireGuard packets only         /
                     \                                           /
                      v                                         v
                     +-------------------------------------------+
                     |                Relay VPS                  |
                     |      UDP relay + framed TCP fallback      |
                     +-------------------------------------------+
                                           |
                                           v
                                   Mobile internet egress
```

## Security model

- Nodes hold two keypairs: Ed25519 identity keys and WireGuard static keys.
- Registration requests are signed with the identity key and rejected if timestamps or nonces are replayed.
- The control plane issues short-lived API JWTs per node and short-lived relay session tokens per session.
- The relay validates the session token offline with `BRN_SESSION_SECRET`.
- Usage ingestion is signed by the relay with `BRN_RELAY_USAGE_SECRET`.
- Each session receives dedicated tunnel IPs, counters, and quota fields.

## Relay behavior

- UDP preferred: both peers send a signed `HELLO` datagram to the relay; once paired, all subsequent raw datagrams are forwarded untouched.
- TCP fallback: both peers open an outbound TCP/TLS stream to the relay and exchange a framed `HELLO` followed by framed `DATA` messages.
- Relay state is sharded in memory and expires idle or expired sessions aggressively.

## Android gateway behavior

- Foreground service maintains registration, heartbeats, and relay connectivity.
- `ConnectivityManager` reacts to network changes and re-establishes relay allocations after Wi-Fi/4G/5G switches.
- The service protects relay sockets from VPN capture and is designed to hand decrypted packets to a userspace network stack for mobile egress.
