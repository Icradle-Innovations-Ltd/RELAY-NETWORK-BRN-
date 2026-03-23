#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$ROOT_DIR"
docker compose -f infra/docker/docker-compose.yml build
docker compose -f infra/docker/docker-compose.yml up -d

echo "BRN services deployed."
echo "Control plane: http://localhost:3000/api/health"
echo "Relay metrics: http://localhost:9090/metrics"
