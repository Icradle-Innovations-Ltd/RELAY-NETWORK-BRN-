# Bandwidth Relay Network (BRN)

BRN is a mobile-powered bandwidth relay network that lets a remote client route traffic through an Android gateway over a CGNAT-safe relay. The design keeps WireGuard end-to-end encrypted while the relay only forwards encrypted packets between the client and gateway.

## Monorepo layout

- `apps/control-plane`: Next.js + TypeScript + Prisma APIs for registration, sessions, heartbeats, and usage ingestion.
- `services/relay`: Go relay that forwards encrypted UDP packets and framed TCP fallback traffic.
- `clients/cli`: Go CLI that registers a client, requests a session, generates WireGuard config, and bridges a local UDP WireGuard endpoint to the relay.
- `android/gateway`: Android app with `VpnService`, node registration, relay transport, and network monitoring scaffolding.
- `packages/contracts`: shared API types for the control plane.
- `packages/go/brnproto`: shared Go token and relay framing helpers.
- `infra/docker`: local Docker setup for Postgres, control plane, and relay.
- `tests/tooling`: gateway simulator, smoke tools, and load generator.

## Architecture

```text
[Client WireGuard] -> [CLI localhost UDP bridge] -> [Relay VPS UDP/TCP] -> [Android BRN app bridge] -> [Gateway WireGuard] -> [VpnService egress]
         |                        |                      |                        |                       |
         +------ control plane JWT/session setup -------+------------------------+-----------------------+
```

Detailed architecture and protocol notes live in `docs/architecture.md` and `docs/protocol.md`.

## Quick start

1. Copy `.env.example` to `apps/control-plane/.env`.
2. Start Postgres, relay, and control plane with `infra/docker/docker-compose.yml`.
3. Run Prisma generate and migrations inside `apps/control-plane`.
4. Register one gateway and one client using the Android app and the Go CLI.
5. Start a session from the CLI, import the generated WireGuard config into a local WireGuard client, and bring the tunnel up.

## MVP behavior

- The control plane signs node JWTs and short-lived relay session tokens.
- The relay validates session tokens offline and never decrypts WireGuard packets.
- The CLI writes WireGuard peer configuration and forwards local encrypted UDP packets to the relay.
- The Android app is structured around a foreground `VpnService` with relay bridging and persistent heartbeats.

## Known implementation boundary

The server and CLI are implemented end-to-end. The Android app includes the production service lifecycle, control-plane integration, and relay transport scaffolding, but still expects integration with a userspace WireGuard backend inside the app build to terminate the encrypted tunnel on-device.
