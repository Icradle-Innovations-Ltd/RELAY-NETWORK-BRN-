# Setup Guide

## 1. Local development

1. Copy the root `.env.example` to `apps/control-plane/.env`.
2. Start infrastructure with `docker compose -f infra/docker/docker-compose.yml up --build`.
3. In another shell, run `corepack pnpm --dir apps/control-plane prisma generate`.
4. Apply your Prisma migrations from `apps/control-plane`.
5. Build or run the Go relay locally with `go run ./services/relay`.
6. Register a gateway:
   - Real Android app: open `android/gateway` in Android Studio, update `CONTROL_PLANE_BASE_URL`, grant VPN permission, and start the service.
   - Simulator: run `go run ./tests/tooling/cmd/gateway-sim --api http://localhost:3000/api --state .gateway-sim.json`.
7. Register a client with `go run ./clients/cli register --api http://localhost:3000/api --state .brn-client.json`.
8. List gateways with `go run ./clients/cli gateways --api http://localhost:3000/api`.
9. Start a session with `go run ./clients/cli connect --gateway-id <gateway-id> --state .brn-client.json --config-out client.conf`.
10. Import `client.conf` into WireGuard and bring the tunnel up while the CLI bridge keeps running.

## 2. VPS deployment

1. Provision Ubuntu 24.04 or later with ports `80`, `443`, `51820/udp`, and `9090` restricted to admins.
2. Install Docker Engine and Docker Compose.
3. Copy the repository and create production env files for the control plane and relay.
4. Update DNS:
   - `api.<domain>` -> VPS public IP
   - `relay.<domain>` -> VPS public IP
5. Add a TLS certificate for relay TCP fallback if you want port `443` wrapped with TLS.
6. Run `infra/scripts/deploy-vps.sh`.
7. Verify:
   - `curl http://127.0.0.1:3000/api/health`
   - `curl http://127.0.0.1:9090/healthz`

## 3. Android gateway notes

- Disable battery optimization for the gateway app.
- Keep the device plugged in for long relay sessions.
- Prefer stable 4G/5G coverage and allow the app to run as a foreground service.
- Integrate a userspace WireGuard backend before production traffic is routed through the phone.

