#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:-all}"
COMPOSE_FILE="$ROOT_DIR/docker/compose.db-matrix.yml"
PROJECT_NAME="xnlp-e2e-$$"
DB_NAME="${XNLP_E2E_DB_NAME:-xnlp_e2e}"
DB_USERNAME_VALUE="${XNLP_E2E_DB_USERNAME:-xnlp_e2e}"
DB_PASSWORD_VALUE="${XNLP_E2E_DB_PASSWORD:-xnlp_e2e}"
MYSQL_PORT="${XNLP_E2E_MYSQL_PORT:-13306}"
POSTGRES_PORT="${XNLP_E2E_POSTGRES_PORT:-15432}"

if [[ ! "$TARGET" =~ ^(mysql|postgres|all)$ ]]; then
  echo "Usage: $0 [mysql|postgres|all]" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Database matrix blocked: Docker is not installed or not available on PATH." >&2
  echo "Install Docker with Compose support, then rerun: $0 ${TARGET}" >&2
  exit 3
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Database matrix blocked: 'docker compose' is unavailable." >&2
  exit 3
fi

cleanup() {
  set +e
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1
}
trap cleanup EXIT INT TERM

wait_healthy() {
  local service="$1"
  local container_id
  container_id="$(docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" ps -q "$service")"
  for _ in $(seq 1 60); do
    local health
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
    if [[ "$health" == "healthy" ]]; then
      return 0
    fi
    if [[ "$health" == "unhealthy" || "$health" == "exited" ]]; then
      docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" logs "$service" >&2
      return 1
    fi
    sleep 2
  done
  echo "Database service ${service} did not become healthy." >&2
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" logs "$service" >&2
  return 1
}

run_database() {
  local profile="$1"
  local db_url
  echo "Starting ${profile} database matrix service..."
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" up -d "$profile"
  wait_healthy "$profile"

  if [[ "$profile" == "mysql" ]]; then
    db_url="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  else
    db_url="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${DB_NAME}"
  fi

  DB_URL="$db_url" \
  DB_USERNAME="$DB_USERNAME_VALUE" \
  DB_PASSWORD="$DB_PASSWORD_VALUE" \
  XNLP_E2E_API_PORT="${XNLP_E2E_API_PORT:-18760}" \
  XNLP_E2E_PROVIDER_PORT="${XNLP_E2E_PROVIDER_PORT:-18880}" \
  tests/e2e/run-profile.sh "$profile"

  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" stop "$profile"
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" rm -f "$profile"
  echo "${profile} database matrix passed."
}

cd "$ROOT_DIR"
case "$TARGET" in
  mysql) run_database mysql ;;
  postgres) run_database postgres ;;
  all)
    run_database mysql
    run_database postgres
    ;;
esac
