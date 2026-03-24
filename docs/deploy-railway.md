# Deploy to Railway

## Architecture

```
┌──────────── Railway ─────────────┐
│  PostgreSQL (plugin)             │
│  Control Plane (Docker service)  │
└──────────────────────────────────┘
        │ HTTPS
        ▼
┌──────────── VPS ─────────────────┐
│  Relay (Docker / binary)         │
│  UDP :51820  TCP :8443  HTTP :9090│
└──────────────────────────────────┘
```

> **Why a VPS for the relay?** Railway does not support UDP port forwarding.
> WireGuard requires UDP :51820, so the relay must run on a host with raw
> UDP support (any $5/mo VPS works — the relay is a ~15 MB Go binary).

---

## 1. Control Plane on Railway

### 1a. Create Railway project

1. Go to [railway.app](https://railway.app) and create a new project.
2. Connect your GitHub repo (`Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-`).
3. Railway detects `apps/control-plane/railway.json` automatically.

### 1b. Add PostgreSQL

1. In the Railway dashboard, click **+ New** → **Database** → **PostgreSQL**.
2. Railway auto-injects `DATABASE_URL` into the control-plane service.

### 1c. Set environment variables

In the control-plane service settings, add these variables:

| Variable | Value | Notes |
|---|---|---|
| `DATABASE_URL` | *(auto-injected by Railway Postgres)* | Already set |
| `BRN_JWT_SECRET` | `openssl rand -hex 32` | Min 32 chars |
| `BRN_SESSION_SECRET` | `openssl rand -hex 32` | Min 32 chars, **must match relay** |
| `BRN_RELAY_USAGE_SECRET` | `openssl rand -hex 32` | Min 32 chars, **must match relay** |
| `RELAY_UDP_ENDPOINT` | `<vps-ip>:51820` | Public IP of relay VPS |
| `RELAY_TCP_ENDPOINT` | `<vps-ip>:8443` | Public IP of relay VPS |

### 1d. Configure build

Railway should auto-detect the Dockerfile from `railway.json`. If not, set manually:

- **Root Directory**: `/` (repo root — Dockerfile uses `COPY` from root)
- **Dockerfile Path**: `infra/docker/control-plane.Dockerfile`
- **Health Check**: `/api/health`

### 1e. Custom domain (optional)

In service settings → **Networking** → **Custom Domain**, add `api.yourdomain.com`.
Railway handles TLS automatically.

### 1f. Deploy

Push to `main`. Railway builds the Docker image, runs Prisma migrations, and starts the server.

Verify:
```bash
curl https://your-app.railway.app/api/health
# {"ok":true,"service":"control-plane","now":"..."}
```

---

## 2. Relay on VPS

The relay needs UDP support, so it runs on a standard VPS.

### 2a. Provision a VPS

Any provider works (Hetzner, DigitalOcean, Vultr, Linode). Requirements:
- Ubuntu 22.04+ or Debian 12+
- Open ports: **51820/udp**, **8443/tcp**, **9090/tcp** (metrics, restrict to admin)
- 1 CPU, 512 MB RAM is sufficient

### 2b. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
```

### 2c. Deploy the relay

Copy the repo to the VPS (or just the relay Dockerfile + source):

```bash
git clone https://github.com/Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-.git
cd RELAY-NETWORK-BRN-
```

Create a `.env.relay` file:

```bash
BRN_SESSION_SECRET=<same-value-as-railway-control-plane>
BRN_RELAY_USAGE_SECRET=<same-value-as-railway-control-plane>
BRN_CONTROL_PLANE_USAGE_URL=https://your-app.railway.app/api/billing/usage
BRN_RELAY_BIND_UDP=:51820
BRN_RELAY_BIND_TCP=:8443
BRN_RELAY_METRICS_ADDR=:9090
```

Build and run:

```bash
docker build -f infra/docker/relay.Dockerfile -t brn-relay .
docker run -d --name brn-relay \
  --restart unless-stopped \
  --env-file .env.relay \
  -p 51820:51820/udp \
  -p 8443:8443 \
  -p 9090:9090 \
  brn-relay
```

Verify:
```bash
curl http://localhost:9090/healthz
# {"ok":true}
```

### 2d. Firewall

```bash
sudo ufw allow 51820/udp   # WireGuard
sudo ufw allow 8443/tcp    # TCP fallback
# Do NOT expose 9090 publicly — metrics only
```

---

## 3. Connecting the pieces

The critical link: **secrets must match** between control plane and relay.

```
Control Plane (Railway)          Relay (VPS)
─────────────────────            ──────────────
BRN_SESSION_SECRET=abc123   ←→   BRN_SESSION_SECRET=abc123
BRN_RELAY_USAGE_SECRET=xyz  ←→   BRN_RELAY_USAGE_SECRET=xyz
```

The control plane tells clients to connect to the relay endpoints:
```
RELAY_UDP_ENDPOINT=<vps-public-ip>:51820
RELAY_TCP_ENDPOINT=<vps-public-ip>:8443
```

---

## 4. DNS setup

| Record | Type | Value |
|---|---|---|
| `api.yourdomain.com` | CNAME | `your-app.railway.app` |
| `relay.yourdomain.com` | A | VPS public IP |

Then update the Railway env vars:
```
RELAY_UDP_ENDPOINT=relay.yourdomain.com:51820
RELAY_TCP_ENDPOINT=relay.yourdomain.com:8443
```

---

## 5. Verifying the full stack

```bash
# 1. Health checks
curl https://api.yourdomain.com/api/health
curl http://<vps-ip>:9090/healthz

# 2. Register a gateway (from Android app or simulator)
# 3. Register a client
go run ./clients/cli register --api https://api.yourdomain.com/api --state .brn-client.json

# 4. List gateways
go run ./clients/cli gateways --api https://api.yourdomain.com/api

# 5. Connect
go run ./clients/cli connect --gateway-id <id> --state .brn-client.json --config-out client.conf
```

---

## Troubleshooting

| Issue | Fix |
|---|---|
| `401 Unauthorized` on session start | Secrets mismatch — ensure `BRN_SESSION_SECRET` is identical on Railway and VPS |
| Prisma migration fails | Check `DATABASE_URL` is set and Postgres plugin is linked |
| Relay health check fails | Check `BRN_SESSION_SECRET` is set (required) |
| UDP not working | Ensure firewall allows 51820/udp on VPS |
| Railway build fails | Ensure root `package.json`, `pnpm-workspace.yaml`, and `tsconfig.base.json` are in repo root |
