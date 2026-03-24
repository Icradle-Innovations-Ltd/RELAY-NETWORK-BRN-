#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/infra/docker/.env.production"
COMPOSE_FILE="$ROOT_DIR/infra/docker/docker-compose.prod.yml"

usage() {
  echo "Usage: $0 [dev|prod]"
  echo "  dev   — local development (default)"
  echo "  prod  — production with TLS via Caddy"
  exit 1
}

MODE="${1:-dev}"

cd "$ROOT_DIR"

case "$MODE" in
  dev)
    docker compose -f infra/docker/docker-compose.yml build
    docker compose -f infra/docker/docker-compose.yml up -d
    echo "BRN dev services deployed."
    echo "  Control plane: http://localhost:3000/api/health"
    echo "  Relay metrics: http://localhost:9090/metrics"
    ;;
  prod)
    if [ ! -f "$ENV_FILE" ]; then
      echo "ERROR: $ENV_FILE not found."
      echo "Copy .env.production.example and fill in real values:"
      echo "  cp infra/docker/.env.production.example infra/docker/.env.production"
      exit 1
    fi

    # Run Prisma migration before starting
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" build
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" run --rm control-plane \
      corepack pnpm prisma migrate deploy
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

    DOMAIN=$(grep '^BRN_DOMAIN=' "$ENV_FILE" | cut -d'=' -f2)
    echo "BRN production services deployed."
    echo "  Control plane: https://${DOMAIN}/api/health"
    echo "  Relay UDP:     ${DOMAIN}:51820"
    echo "  Relay TCP:     ${DOMAIN}:8443"
    echo "  Metrics:       http://127.0.0.1:9090/metrics (local only)"
    ;;
  *)
    usage
    ;;
esac
