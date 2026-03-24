#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────
# BRN — EC2 Instance Setup (run once after launching the instance)
# Tested on: Amazon Linux 2023 / Ubuntu 24.04
# Instance: t3.micro (free tier) or t3.small ($8/mo)
# ─────────────────────────────────────────────────────────
set -euo pipefail

echo "=== BRN EC2 Setup ==="

# ── 1. Detect OS ──
if [ -f /etc/os-release ]; then
  . /etc/os-release
  OS_ID="${ID}"
else
  echo "Cannot detect OS. Exiting."
  exit 1
fi

# ── 2. Install Docker ──
if ! command -v docker &>/dev/null; then
  echo "Installing Docker..."
  if [[ "$OS_ID" == "amzn" ]]; then
    sudo dnf update -y
    sudo dnf install -y docker
    sudo systemctl enable --now docker
  else
    # Ubuntu / Debian
    curl -fsSL https://get.docker.com | sudo sh
  fi
  sudo usermod -aG docker "$USER"
  echo "Docker installed. You may need to re-login for group changes."
fi

# ── 3. Install Docker Compose plugin ──
if ! docker compose version &>/dev/null; then
  echo "Installing Docker Compose plugin..."
  if [[ "$OS_ID" == "amzn" ]]; then
    sudo mkdir -p /usr/local/lib/docker/cli-plugins
    COMPOSE_VERSION=$(curl -s https://api.github.com/repos/docker/compose/releases/latest | grep '"tag_name"' | cut -d'"' -f4)
    sudo curl -SL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  fi
  # Ubuntu's get.docker.com script includes compose
fi

# ── 4. Install git ──
if ! command -v git &>/dev/null; then
  echo "Installing git..."
  if [[ "$OS_ID" == "amzn" ]]; then
    sudo dnf install -y git
  else
    sudo apt-get update && sudo apt-get install -y git
  fi
fi

# ── 5. Add swap (t3.micro only has 1 GB RAM — needed for Docker builds) ──
if [ ! -f /swapfile ]; then
  echo "Creating 2 GB swap..."
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# ── 6. Firewall (iptables — EC2 security groups are the primary firewall) ──
echo ""
echo "=== IMPORTANT: Configure your EC2 Security Group ==="
echo "Add these inbound rules:"
echo "  TCP  80     0.0.0.0/0   (HTTP → Caddy redirect)"
echo "  TCP  443    0.0.0.0/0   (HTTPS → Control plane API)"
echo "  UDP  443    0.0.0.0/0   (HTTP/3 — optional)"
echo "  UDP  51820  0.0.0.0/0   (WireGuard relay)"
echo "  TCP  8443   0.0.0.0/0   (TCP fallback relay)"
echo "  TCP  22     your-ip/32  (SSH — restrict to your IP)"
echo ""

# ── 7. Clone repo (if not already present) ──
BRN_DIR="$HOME/RELAY-NETWORK-BRN"
if [ ! -d "$BRN_DIR" ]; then
  echo "Cloning BRN repository..."
  git clone https://github.com/Icradle-Innovations-Ltd/RELAY-NETWORK-BRN-.git "$BRN_DIR"
fi

# ── 8. Generate .env.production if missing ──
ENV_FILE="$BRN_DIR/infra/docker/.env.production"
if [ ! -f "$ENV_FILE" ]; then
  echo "Generating .env.production with random secrets..."

  # Prompt for domain
  read -rp "Enter your domain (e.g. brn.example.com): " BRN_DOMAIN

  # Generate secrets
  PG_PASS=$(openssl rand -hex 32)
  JWT_SECRET=$(openssl rand -hex 32)
  SESSION_SECRET=$(openssl rand -hex 32)
  USAGE_SECRET=$(openssl rand -hex 32)

  cat > "$ENV_FILE" <<EOF
# ─── BRN Production Environment (auto-generated) ───
BRN_DOMAIN=${BRN_DOMAIN}

POSTGRES_USER=brn
POSTGRES_PASSWORD=${PG_PASS}

BRN_JWT_SECRET=${JWT_SECRET}
BRN_SESSION_SECRET=${SESSION_SECRET}
BRN_RELAY_USAGE_SECRET=${USAGE_SECRET}
EOF

  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE (permissions: 600)"
  echo ""
  echo "SAVE THESE SECRETS — they are not stored anywhere else."
  echo "  Postgres password: ${PG_PASS}"
  echo "  JWT secret:        ${JWT_SECRET}"
  echo "  Session secret:    ${SESSION_SECRET}"
  echo "  Usage secret:      ${USAGE_SECRET}"
  echo ""
else
  echo ".env.production already exists, skipping."
fi

# ── 9. Deploy ──
echo "=== Deploying BRN ==="
cd "$BRN_DIR"
bash infra/scripts/deploy-vps.sh prod

echo ""
echo "=== BRN Deployed! ==="
DOMAIN=$(grep '^BRN_DOMAIN=' "$ENV_FILE" | cut -d'=' -f2)
echo "  API:        https://${DOMAIN}/api/health"
echo "  Relay UDP:  ${DOMAIN}:51820"
echo "  Relay TCP:  ${DOMAIN}:8443"
echo ""
echo "Next steps:"
echo "  1. Point your domain A record to this EC2's public IP"
echo "  2. Wait for DNS propagation (check: dig ${DOMAIN})"
echo "  3. Caddy auto-provisions TLS once DNS resolves"
echo "  4. Test: curl https://${DOMAIN}/api/health"
