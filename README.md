# Bandwidth Relay Network (BRN)

**BRN** is a mobile-powered bandwidth relay network that lets an Android **Client** phone route all its internet traffic through an Android **Gateway** phone. The Gateway shares its data connection — whether through a cloud relay server or directly over **WiFi Direct** when both phones are nearby.

WireGuard encryption is maintained end-to-end; the relay server only forwards opaque encrypted packets and never sees plaintext.

---

## How It Works

### Mode 1 — Cloud Relay (both phones have internet)

```
Phone B (Client)                          Phone A (Gateway)
   BRN Client App                           BRN Gateway App
        │                                        │
        │── encrypted UDP ──► Relay Server ◄── encrypted UDP ──│
        │                    (EC2 / VPS)                       │
        │                                        │
        └── traffic exits via Phone A's connection ──► Internet
```

- Client's traffic is routed through the Gateway's internet connection
- Useful for: privacy, accessing Gateway's geo-location, sharing faster bandwidth
- Both phones need *some* internet to reach the relay server

### Mode 2 — WiFi Direct (no internet needed on Client) 🆕

```
Phone B (Client)                Phone A (Gateway)
   BRN Client App                BRN Gateway App
        │                              │
        │── WiFi Direct P2P ──────────│
        │   (192.168.49.x)            │
        │                              │
        │── WireGuard tunnel ─────────│
        │                              │──► Mobile Data ──► Internet
        └── all traffic exits via      │
            Phone A's connection ──────┘
```

- **Phone B needs ZERO internet** — connects directly to Phone A over WiFi Direct
- Phone A shares its mobile data / WiFi through the WireGuard tunnel
- No relay server needed — fully peer-to-peer local connection
- Range: ~200ft / 60m (WiFi Direct range)

---

## Features

- **WireGuard end-to-end encryption** — relay never sees plaintext
- **Cloud relay mode** — works anywhere in the world via UDP relay
- **WiFi Direct mode** — zero-internet local sharing between nearby phones
- **Automatic TCP fallback** — if UDP is blocked, falls back to TCP framing
- **Session management** — control plane handles registration, JWT auth, session tokens
- **Usage tracking** — per-session bandwidth metering
- **Rate limiting** — protects all API endpoints
- **Auto TLS** — Caddy provisions Let's Encrypt certificates automatically

---

## Monorepo Layout

```
├── android/
│   ├── gateway/          # Gateway Android app (shares internet)
│   └── client/           # Client Android app (uses shared internet)
├── apps/
│   └── control-plane/    # Next.js + Prisma API server
├── services/
│   └── relay/            # Go UDP/TCP relay server
├── clients/
│   └── cli/              # Go CLI client (alternative to Android client)
├── packages/
│   ├── contracts/        # Shared TypeScript API types
│   └── go/brnproto/      # Shared Go token & framing helpers
├── infra/
│   ├── docker/           # Docker Compose, Dockerfiles, production config
│   └── scripts/          # Deployment scripts
├── tests/
│   └── tooling/          # Gateway simulator, load testing
└── docs/                 # Architecture, protocol, security, deployment docs
```

---

## Architecture

```
┌──────────────── Cloud Infrastructure ────────────────┐
│                                                       │
│  Caddy (:443 TLS) ──► Control Plane (:3000)          │
│  Postgres (:5432)      - Registration & Auth          │
│  Relay (:51820/udp     - Session management           │
│         :8443/tcp)     - Heartbeat monitoring          │
│                        - Usage billing                 │
└───────────────────────────────────────────────────────┘
         ▲                              ▲
         │ encrypted UDP/TCP            │ encrypted UDP/TCP
         │                              │
   ┌─────┴─────┐                 ┌──────┴──────┐
   │  Client    │  ◄─WiFi Direct─►  Gateway    │
   │  Phone B   │   (local mode)  │  Phone A   │
   └────────────┘                 └─────────────┘
```

### Protocol Flow (Cloud Relay Mode)

1. **Gateway registers** → POST `/api/nodes/register` (self-signed EC P-256)
2. **Client signs up / logs in** → POST `/api/auth/signup` or `/api/auth/login`
3. **Client registers as node** → POST `/api/auth/register-client` (JWT auth)
4. **Client lists gateways** → GET `/api/nodes/available`
5. **Client starts session** → POST `/api/sessions/start` → receives relay token + tunnel IPs
6. **Gateway receives session via heartbeat** → POST `/api/nodes/heartbeat` → gets assigned sessions
7. **Both sides send BRN1 HELLO to relay** → relay bridges encrypted UDP packets
8. **WireGuard tunnel established** → all client traffic routes through gateway

### Protocol Flow (WiFi Direct Mode)

1. **Gateway starts WiFi Direct group** (becomes group owner)
2. **Client discovers and connects** to Gateway's WiFi Direct group
3. **WireGuard tunnel established directly** over WiFi Direct IP (192.168.49.x)
4. **Client traffic routes through Gateway** → exits via Gateway's mobile data
5. **No relay server or internet needed on client side**

---

## Production Deployment

**Live instance**: `https://relay.healthlinks.ug`

### Infrastructure

| Component      | Technology         | Port       |
|---------------|--------------------|------------|
| Control Plane | Next.js 15 + Prisma | 3000 (internal) |
| Relay Server  | Go                 | 51820/UDP, 8443/TCP |
| Database      | PostgreSQL 16      | 5432 (internal) |
| TLS Proxy     | Caddy 2            | 80, 443    |
| Hosting       | AWS EC2 t3.micro   | eu-north-1 (Stockholm) |

### Quick Deploy

```bash
# SSH to your EC2 instance
ssh -i your-key.pem ubuntu@your-ip

# Clone and deploy
git clone https://github.com/Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-.git
cd RELAY-NETWORK-BRN-

# Create production env file
cp infra/docker/.env.example infra/docker/.env.production
# Edit .env.production with your domain, secrets, DATABASE_URL

# Start everything
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.production up -d

# Sync database schema
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.production \
  exec control-plane npx prisma db push

# Verify
curl https://your-domain.com/api/health
# → {"ok":true}
```

See [docs/deploy-aws.md](docs/deploy-aws.md) for the complete step-by-step guide.

---

## Android Apps

### BRN Gateway (Phone A — shares internet)

The gateway app registers as a relay node, maintains heartbeats, receives session assignments, and bridges WireGuard traffic through its internet connection.

**Key capabilities:**
- EC P-256 identity key generation (Android KeyStore)
- WireGuard Curve25519 key management
- Self-signed registration with the control plane
- Persistent heartbeat loop with session assignment handling
- Relay transport (UDP bridge to cloud relay)
- WiFi Direct group owner (local mode)
- Foreground VPN service with notification

### BRN Client (Phone B — uses shared internet)

The client app authenticates users, discovers available gateways, connects through them, and establishes a full WireGuard VPN tunnel.

**Key capabilities:**
- User signup/login with JWT authentication
- Client node registration
- Gateway discovery and selection
- Session initiation with relay token exchange
- WireGuard VPN tunnel with full traffic routing (`0.0.0.0/0`)
- WiFi Direct peer discovery (local mode)
- Automatic transport fallback (UDP → TCP)

### Building the APKs

```bash
# Prerequisites
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android/sdk"

# Build Gateway APK
cd android/gateway
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build Client APK
cd android/client
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Testing Phone-to-Phone

#### Cloud Relay Mode
1. Install **Gateway APK** on Phone A (has internet)
2. Install **Client APK** on Phone B (has internet)
3. On Phone A: Open BRN Gateway → tap **Start Gateway**
4. On Phone B: Open BRN Client → **Sign up** → gateways appear → tap one → **VPN permission** → connected
5. Phone B's traffic now routes through Phone A

#### WiFi Direct Mode (No Internet on Phone B)
1. Install **Gateway APK** on Phone A (has mobile data)
2. Install **Client APK** on Phone B (NO internet needed)
3. On Phone A: Open BRN Gateway → tap **Start Gateway** → tap **Start WiFi Direct**
4. On Phone B: Open BRN Client → tap **Local Connect** → select Phone A from discovered peers
5. WiFi Direct connects → WireGuard tunnel establishes → Phone B browses via Phone A's data

---

## API Endpoints

| Method | Path                     | Auth          | Description                    |
|--------|--------------------------|---------------|--------------------------------|
| GET    | `/api/health`            | None          | Health check                   |
| POST   | `/api/auth/signup`       | None          | User registration              |
| POST   | `/api/auth/login`        | None          | User login                     |
| POST   | `/api/auth/register-client` | User JWT   | Register client node           |
| POST   | `/api/nodes/register`    | Self-signed   | Register gateway node          |
| POST   | `/api/nodes/heartbeat`   | Node JWT      | Gateway heartbeat + sessions   |
| GET    | `/api/nodes/available`   | Node JWT      | List active gateways           |
| POST   | `/api/sessions/start`    | Node JWT      | Start a session with a gateway |
| POST   | `/api/billing/usage`     | HMAC          | Report session usage           |

---

## Security

- **EC P-256** identity keys stored in Android KeyStore (hardware-backed)
- **Curve25519** WireGuard keys for tunnel encryption
- **HS256 JWT** for node and user authentication
- **HMAC-SHA256** signed session tokens (validated offline by relay)
- **Registration nonce replay protection**
- **Rate limiting** on all API endpoints
- **TLS everywhere** — Caddy auto-provisions Let's Encrypt certs

See [docs/security-checklist.md](docs/security-checklist.md) for the full audit.

---

## Development

### Local Setup

```bash
# Install dependencies
pnpm install

# Start local infrastructure
docker compose -f infra/docker/docker-compose.yml up -d

# Run control plane
cd apps/control-plane
npx prisma generate && npx prisma db push
pnpm dev

# Run relay
cd services/relay
go run main.go
```

### Environment Variables

Key variables for `.env`:

```
DATABASE_URL=postgresql://brn:brn@localhost:5432/brn
BRN_JWT_SECRET=your-jwt-secret
BRN_SESSION_SECRET=your-session-token-secret
BRN_RELAY_HMAC_SECRET=your-relay-hmac-secret
BRN_DOMAIN=your-domain.com
RELAY_UDP_ENDPOINT=your-domain.com:51820
RELAY_TCP_ENDPOINT=your-domain.com:8443
```

---

## License

Proprietary — Icradle Innovations Ltd.
