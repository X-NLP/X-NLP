#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="${1:-}"
API_PORT="${XNLP_E2E_API_PORT:-18760}"
PROVIDER_PORT="${XNLP_E2E_PROVIDER_PORT:-18880}"
BASE_URL="http://127.0.0.1:${API_PORT}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xnlp-profile-e2e.XXXXXX")"
SERVER_PID=""
PROVIDER_PID=""

if [[ ! "$PROFILE" =~ ^(h2|mysql|postgres)$ ]]; then
  echo "Usage: $0 <h2|mysql|postgres>" >&2
  exit 2
fi

need_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

cleanup() {
  local status=$?
  set +e
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1
    wait "$SERVER_PID" >/dev/null 2>&1
  fi
  if [[ -n "$PROVIDER_PID" ]]; then
    kill "$PROVIDER_PID" >/dev/null 2>&1
    wait "$PROVIDER_PID" >/dev/null 2>&1
  fi
  if [[ $status -ne 0 || "${XNLP_E2E_KEEP_LOGS:-false}" == "true" ]]; then
    mkdir -p "$ROOT_DIR/target/e2e-logs"
    cp "$WORK_DIR/server.log" "$ROOT_DIR/target/e2e-logs/${PROFILE}-server.log" 2>/dev/null || true
    cp "$WORK_DIR/provider.log" "$ROOT_DIR/target/e2e-logs/${PROFILE}-provider.log" 2>/dev/null || true
    echo "E2E logs: $ROOT_DIR/target/e2e-logs/${PROFILE}-*.log" >&2
  fi
  rm -rf "$WORK_DIR"
  exit "$status"
}
trap cleanup EXIT INT TERM

need_tool curl
need_tool jq
need_tool java
need_tool python3

cd "$ROOT_DIR"

if [[ "${XNLP_E2E_SKIP_BUILD:-false}" != "true" ]]; then
  need_tool mvn
  MAVEN_COMMAND=(mvn)
  if [[ -n "${XNLP_MAVEN_ARGS:-}" ]]; then
    read -r -a EXTRA_MAVEN_ARGS <<<"${XNLP_MAVEN_ARGS}"
    MAVEN_COMMAND+=("${EXTRA_MAVEN_ARGS[@]}")
  fi
  MAVEN_COMMAND+=(-pl xnlp-server -am package -DskipTests)
  echo "+ ${MAVEN_COMMAND[*]}"
  "${MAVEN_COMMAND[@]}"
fi

if [[ ! -d xnlp-server/target ]]; then
  echo "Server target directory not found. Build it first or unset XNLP_E2E_SKIP_BUILD." >&2
  exit 1
fi
JAR_FILE="$(find xnlp-server/target -maxdepth 1 -type f -name 'xnlp-server-*.jar' ! -name '*.original' | sort | tail -n 1)"
if [[ -z "$JAR_FILE" ]]; then
  echo "Server jar not found. Build it first or unset XNLP_E2E_SKIP_BUILD." >&2
  exit 1
fi

if [[ "$PROFILE" == "h2" ]]; then
  DB_URL_VALUE="${DB_URL:-jdbc:h2:file:${WORK_DIR}/xnlp;MODE=MySQL;DB_CLOSE_DELAY=-1}"
  DB_USERNAME_VALUE="${DB_USERNAME:-sa}"
  DB_PASSWORD_VALUE="${DB_PASSWORD:-}"
else
  if [[ -z "${DB_URL:-}" || -z "${DB_USERNAME:-}" ]]; then
    echo "DB_URL and DB_USERNAME are required for ${PROFILE} E2E." >&2
    exit 2
  fi
  DB_URL_VALUE="$DB_URL"
  DB_USERNAME_VALUE="$DB_USERNAME"
  DB_PASSWORD_VALUE="${DB_PASSWORD:-}"
fi

python3 tests/e2e/mock-openai-provider.py --port "$PROVIDER_PORT" >"$WORK_DIR/provider.log" 2>&1 &
PROVIDER_PID=$!
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PROVIDER_PORT}/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
if ! curl -fsS "http://127.0.0.1:${PROVIDER_PORT}/health" >/dev/null 2>&1; then
  echo "Mock provider failed to start." >&2
  exit 1
fi

SPRING_PROFILES_ACTIVE="$PROFILE" \
DB_URL="$DB_URL_VALUE" \
DB_USERNAME="$DB_USERNAME_VALUE" \
DB_PASSWORD="$DB_PASSWORD_VALUE" \
XNLP_PORT="$API_PORT" \
SPRING_AI_MODEL_CHAT=openai \
SPRING_AI_MODEL_EMBEDDING=openai \
OPENAI_API_KEY=xnlp-e2e-placeholder \
OPENAI_BASE_URL="http://127.0.0.1:${PROVIDER_PORT}" \
OPENAI_CHAT_MODEL=xnlp-e2e-chat \
OPENAI_EMBEDDING_MODEL=xnlp-e2e-embedding \
OTEL_SAMPLING_PROBABILITY=0 \
LOG_PATH="$WORK_DIR/logs" \
java -jar "$JAR_FILE" >"$WORK_DIR/server.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 120); do
  if curl -fsS "${BASE_URL}/readyz" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    echo "X-NLP server exited before becoming ready." >&2
    tail -n 120 "$WORK_DIR/server.log" >&2 || true
    exit 1
  fi
  sleep 1
done
if ! curl -fsS "${BASE_URL}/readyz" >/dev/null 2>&1; then
  echo "X-NLP server did not become ready within 120 seconds." >&2
  tail -n 120 "$WORK_DIR/server.log" >&2 || true
  exit 1
fi

echo "Running Release 0.3 API E2E with ${PROFILE} at ${BASE_URL}"
XNLP_API_BASE="$BASE_URL" tests/e2e/release-0.3-api.sh

echo "Running Release 0.4 retrieval evaluation E2E with ${PROFILE} at ${BASE_URL}"
XNLP_API_BASE="$BASE_URL" XNLP_PROVIDER_BASE="http://127.0.0.1:${PROVIDER_PORT}" tests/e2e/release-0.4-retrieval-evaluation.sh
