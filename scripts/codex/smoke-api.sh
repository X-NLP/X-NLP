#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8080}"
ENDPOINTS=(
  "/health"
  "/api/v1/models"
  "/api/v1/models/capabilities"
  "/api/v1/datasets"
  "/api/v1/evaluations"
)

echo "X-NLP API smoke base: ${BASE_URL}"

for endpoint in "${ENDPOINTS[@]}"; do
  url="${BASE_URL}${endpoint}"
  tmp_body="$(mktemp)"
  status="$(curl -sS -o "$tmp_body" -w '%{http_code}' "$url" || true)"
  preview="$(tr '\n' ' ' < "$tmp_body" | cut -c 1-180)"
  rm -f "$tmp_body"

  echo "${status} ${endpoint} ${preview}"

  if [[ ! "$status" =~ ^2 ]]; then
    echo "Smoke failed for ${url}. Start backend with: cd xnlp-server && mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080" >&2
    exit 1
  fi
done
