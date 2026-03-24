# Deploy BRN on AWS EC2 (Single Instance)

Everything on one box: Control Plane + Relay + Postgres via Docker Compose.

## Architecture

```
┌─────────── EC2 (t3.micro / t3.small) ───────────┐
│                                                   │
│  Caddy (:80, :443)  ─► Control Plane (:3000)     │
│  Postgres (:5432 internal)                        │
│  Relay (:51820/udp, :8443/tcp)                    │
│                                                   │
└───────────────────────────────────────────────────┘
```

**Cost**: ~$0 (t3.micro free tier, 1 GB RAM) or ~$8/mo (t3.small, 2 GB RAM).

---

## Prerequisites

- An AWS account
- A domain name with DNS you can edit (for TLS)
- An SSH key pair registered in AWS

---

## 1. Launch an EC2 Instance

1. Go to [EC2 Console → Launch Instance](https://console.aws.amazon.com/ec2/v2/home#LaunchInstances)
2. **AMI**: Amazon Linux 2023 or Ubuntu 24.04 LTS
3. **Instance type**: `t3.micro` (free tier) or `t3.small` (2 GB RAM — recommended)
4. **Key pair**: Select your SSH key
5. **Network settings**: Create a new security group with these rules:

| Type         | Port    | Protocol | Source       | Purpose                |
|-------------|---------|----------|--------------|------------------------|
| SSH          | 22      | TCP      | Your IP/32   | Admin access           |
| HTTP         | 80      | TCP      | 0.0.0.0/0   | Caddy redirect → HTTPS |
| HTTPS        | 443     | TCP      | 0.0.0.0/0   | Control Plane API      |
| Custom UDP   | 443     | UDP      | 0.0.0.0/0   | HTTP/3 (optional)      |
| Custom UDP   | 51820   | UDP      | 0.0.0.0/0   | WireGuard relay        |
| Custom TCP   | 8443    | TCP      | 0.0.0.0/0   | TCP fallback relay     |

6. **Storage**: 20 GB gp3 (default is fine)
7. Launch the instance

---

## 2. Point Your Domain

Create an **A record** for your domain pointing to the EC2 public IP:

```
brn.example.com  →  A  →  54.xx.xx.xx
```

Caddy auto-provisions TLS via Let's Encrypt once DNS resolves.

---

## 3. SSH In and Run Setup

```bash
# Amazon Linux 2023
ssh -i your-key.pem ec2-user@<public-ip>

# Ubuntu 24.04
ssh -i your-key.pem ubuntu@<public-ip>
```

Then run the one-line setup:

```bash
curl -fsSL https://raw.githubusercontent.com/Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-/main/infra/scripts/setup-ec2.sh | bash
```

Or clone and run manually:

```bash
git clone https://github.com/Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-.git ~/RELAY-NETWORK-BRN
bash ~/RELAY-NETWORK-BRN/infra/scripts/setup-ec2.sh
```

The script will:
1. Install Docker, Docker Compose, and git
2. Create 2 GB swap (needed for t3.micro builds)
3. Clone the repository
4. Generate `.env.production` with random secrets (prompts for your domain)
5. Build and start all services

---

## 4. Verify

```bash
# Health check
curl https://brn.example.com/api/health

# Check all containers are running
cd ~/RELAY-NETWORK-BRN
docker compose -f infra/docker/docker-compose.prod.yml ps

# View logs
docker compose -f infra/docker/docker-compose.prod.yml logs -f
```

Expected health response:
```json
{"ok": true, "service": "control-plane", "now": "2025-..."}
```

---

## 5. Managing the Deployment

### Updating
```bash
cd ~/RELAY-NETWORK-BRN
git pull origin main
bash infra/scripts/deploy-vps.sh prod
```

### Viewing logs
```bash
cd ~/RELAY-NETWORK-BRN
docker compose -f infra/docker/docker-compose.prod.yml logs -f relay
docker compose -f infra/docker/docker-compose.prod.yml logs -f control-plane
```

### Restarting
```bash
cd ~/RELAY-NETWORK-BRN
docker compose -f infra/docker/docker-compose.prod.yml restart
```

### Stopping
```bash
cd ~/RELAY-NETWORK-BRN
docker compose -f infra/docker/docker-compose.prod.yml down
```

### Backup Postgres
```bash
docker compose -f infra/docker/docker-compose.prod.yml exec postgres \
  pg_dump -U brn brn > backup-$(date +%Y%m%d).sql
```

---

## 6. Secrets

Secrets are stored in `infra/docker/.env.production` (chmod 600). Back them up securely — they are not recoverable if lost.

| Variable | Shared with |
|---|---|
| `BRN_SESSION_SECRET` | Control plane + Relay (must match) |
| `BRN_RELAY_USAGE_SECRET` | Control plane + Relay (must match) |
| `BRN_JWT_SECRET` | Control plane only |
| `POSTGRES_PASSWORD` | Postgres + Control plane |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `curl: connection refused` on :443 | Check EC2 security group allows TCP 443 inbound |
| Caddy says "TLS handshake error" | DNS not pointed yet — run `dig brn.example.com` |
| Control plane CrashLoop | Check `docker logs brn-control-plane-1` — likely missing env var |
| Relay not accepting connections | Check security group allows UDP 51820 inbound |
| Out of memory during build | Ensure swap exists: `swapon --show` |
| Prisma migration fails | Run manually: `docker compose run --rm control-plane sh -c "corepack pnpm prisma migrate deploy"` |

---

## Alternative: Railway + VPS Split

If you prefer managed hosting for the control plane, see [deploy-railway.md](deploy-railway.md).
Railway handles the control plane + Postgres; the relay still needs a VPS/EC2 for UDP support.
