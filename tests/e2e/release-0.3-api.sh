#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8760}"
RUN_ID="${XNLP_E2E_RUN_ID:-$(date +%s)-$$}"
MODEL_NAME="codex-test-model-${RUN_ID}"
DATASET_NAME="codex-test-dataset-${RUN_ID}"
REQUEST_ID="codex-test-request-${RUN_ID}"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xnlp-api-e2e.XXXXXX")"
MODEL_CREATED=false
DATASET_ID=""
AUTH_ARGS=(-H "X-Tenant-ID: ${XNLP_TENANT_ID:-default}")
MAX_BENCHMARK_P95_MS="${XNLP_E2E_MAX_BENCHMARK_P95_MS:-5000}"

if [[ ! "$MAX_BENCHMARK_P95_MS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
    || ! awk -v value="$MAX_BENCHMARK_P95_MS" 'BEGIN { exit !(value > 0) }'; then
  echo "XNLP_E2E_MAX_BENCHMARK_P95_MS must be a positive number." >&2
  exit 2
fi

if [[ -n "${XNLP_API_KEY:-}" ]]; then
  AUTH_ARGS+=(-H "${XNLP_SECURITY_HEADER:-X-API-Key}: ${XNLP_API_KEY}")
fi

need_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

cleanup() {
  set +e
  if [[ -n "$DATASET_ID" ]]; then
    curl -sS -o /dev/null -X DELETE "${AUTH_ARGS[@]}" "${BASE_URL}/api/v1/datasets/${DATASET_ID}"
  fi
  if [[ "$MODEL_CREATED" == true ]]; then
    curl -sS -o /dev/null -X POST "${AUTH_ARGS[@]}" "${BASE_URL}/api/v1/models/${MODEL_NAME}/unload"
    curl -sS -o /dev/null -X DELETE "${AUTH_ARGS[@]}" "${BASE_URL}/api/v1/models/${MODEL_NAME}"
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

need_tool curl
need_tool jq

LAST_STATUS=""
LAST_BODY=""
request() {
  local method="$1"
  local path="$2"
  local expected="$3"
  local payload="${4:-}"
  local body_file="${TMP_DIR}/body.json"
  local curl_args=(-sS -o "$body_file" -w '%{http_code}' -X "$method" "${AUTH_ARGS[@]}" -H "X-Request-ID: ${REQUEST_ID}")
  if [[ -n "$payload" ]]; then
    curl_args+=(-H 'Content-Type: application/json' --data "$payload")
  fi

  LAST_STATUS="$(curl "${curl_args[@]}" "${BASE_URL}${path}" || true)"
  LAST_BODY="$(cat "$body_file")"
  if [[ "$LAST_STATUS" != "$expected" ]]; then
    echo "E2E request failed: ${method} ${path}; expected ${expected}, got ${LAST_STATUS}" >&2
    jq . "$body_file" 2>/dev/null || cat "$body_file" >&2
    exit 1
  fi
  echo "PASS ${LAST_STATUS} ${method} ${path}"
}

assert_jq() {
  local argument_count="$#"
  local description="${!argument_count}"
  local -a jq_args=("${@:1:$((argument_count - 1))}")
  if ! jq -e "${jq_args[@]}" >/dev/null <<<"$LAST_BODY"; then
    echo "E2E assertion failed: ${description}" >&2
    jq . <<<"$LAST_BODY" 2>/dev/null || printf '%s\n' "$LAST_BODY" >&2
    exit 1
  fi
}

echo "X-NLP Release 0.3 API E2E"
echo "Base URL: ${BASE_URL}"
echo "Run ID: ${RUN_ID}"

request GET /health 200
assert_jq '.status == "UP" and .probes.readiness.status == "READY"' "health reports UP and READY"

request GET /api/v1/models/diagnostics 200
assert_jq '.activeProbe == false and (.diagnostics | type == "array") and (.diagnostics | length > 0)' "passive diagnostics contract"

request POST /api/v1/models/diagnostics/probe 200
assert_jq '.activeProbe == true and (.diagnostics | type == "array") and (.checkedAt | type == "string")' "active diagnostics contract"

MODEL_PAYLOAD="$(jq -nc --arg name "$MODEL_NAME" '{
  name: $name,
  type: "CHAT",
  protocol: "SPRING_AI_CHAT",
  source: "CUSTOM",
  provider: "openai",
  model_name: "xnlp-e2e-chat",
  version: "e2e",
  backend: "spring-ai",
  device: "cpu",
  max_input_length: 4096,
  max_output_length: 256,
  options: {environment: "e2e"}
}')"
request POST /api/v1/models 201 "$MODEL_PAYLOAD"
MODEL_CREATED=true
assert_jq --arg name "$MODEL_NAME" '.name == $name and .status == "configured" and .apiKeySet == false' "model profile created without credential exposure"

request POST /api/v1/models/diagnostics/probe 200
assert_jq --arg name "$MODEL_NAME" 'any(.diagnostics[]; .name == $name and .configured == true and .reachable == true and .usable == true and .status == "usable")' "created Spring AI profile is usable"

request GET /api/v1/models 200
assert_jq --arg name "$MODEL_NAME" 'any(.[]; .name == $name)' "model profile appears in catalog"

request POST "/api/v1/models/${MODEL_NAME}/activate" 200
assert_jq --arg name "$MODEL_NAME" '.name == $name and .status == "loaded"' "model runtime activated"

request GET /api/v1/models/runtime 200
assert_jq --arg name "$MODEL_NAME" 'any(.[]; .name == $name and .status == "loaded")' "runtime list contains activated model"

request POST "/api/v1/models/${MODEL_NAME}/predict" 200 '{"text":"release 0.3 external prediction","max_length":128,"parameters":{"temperature":0.1}}'
assert_jq --arg name "$MODEL_NAME" '.model == $name and (.text | startswith("mock-response:")) and (.elapsedSeconds >= 0)' "prediction uses deterministic provider"

request POST "/api/v1/benchmark/${MODEL_NAME}" 200 '{"requests":4,"concurrency":2,"text":"release 0.3 benchmark"}'
assert_jq --arg name "$MODEL_NAME" '.model == $name and .totalRequests == 4 and .successfulRequests == 4 and .failedRequests == 0 and .successRate == 1 and (.latenciesMs | length == 4)' "benchmark result contract"
assert_jq --argjson maxP95 "$MAX_BENCHMARK_P95_MS" '.latencyP95Ms >= 0 and .latencyP95Ms <= $maxP95 and .requestsPerSecond > 0' "benchmark P95 performance gate"

DATASET_PAYLOAD="$(jq -nc --arg name "$DATASET_NAME" '{
  name: $name,
  description: "Release 0.3 repeatable E2E dataset",
  taskType: "TEXT_CLASSIFICATION",
  entries: [
    {input: "alpha", expectedOutput: "A", labels: {split: "test"}, metadata: {source: "e2e"}},
    {input: "beta", expectedOutput: "B", labels: {split: "test"}, metadata: {source: "e2e"}}
  ]
}')"
request POST /api/v1/datasets 201 "$DATASET_PAYLOAD"
DATASET_ID="$(jq -r '.id' <<<"$LAST_BODY")"
assert_jq --arg name "$DATASET_NAME" '.name == $name and .entryCount == 2 and (.id | type == "string")' "dataset created with entries"

request GET "/api/v1/datasets/${DATASET_ID}" 200
assert_jq --arg name "$DATASET_NAME" '.name == $name and (.entries | length == 2)' "dataset detail persisted"

request GET "/api/v1/datasets/${DATASET_ID}/entries?page=0&size=1" 200
assert_jq '.page == 0 and .size == 1 and .total == 2 and (.items | length == 1) and (.entries | length == 1)' "dataset page contract"

request PUT "/api/v1/datasets/${DATASET_ID}" 200 "$(jq -c '.description = "Release 0.3 updated E2E dataset"' <<<"$DATASET_PAYLOAD")"
assert_jq '.description == "Release 0.3 updated E2E dataset" and .entryCount == 2' "dataset update persisted"

request POST "/api/v1/benchmark/${MODEL_NAME}" 400 '{"requests":0,"concurrency":1,"text":"invalid"}'
assert_jq --arg requestId "$REQUEST_ID" '.status == 400 and .error == "validation_error" and .requestId == $requestId and (.violations | length > 0) and (has("stackTrace") | not)' "stable validation error contract"

request POST /api/v1/models/codex-test-missing/predict 404 '{"text":"missing model"}'
assert_jq --arg requestId "$REQUEST_ID" '.status == 404 and .error == "model_not_found" and .requestId == $requestId and (has("stackTrace") | not)' "stable model-not-found contract"

request DELETE "/api/v1/datasets/${DATASET_ID}" 204
DATASET_ID=""
request POST "/api/v1/models/${MODEL_NAME}/unload" 200
request DELETE "/api/v1/models/${MODEL_NAME}" 204
MODEL_CREATED=false

request GET /api/v1/models 200
assert_jq --arg name "$MODEL_NAME" 'all(.[]; .name != $name)' "model cleanup completed"
request GET /api/v1/datasets 200
assert_jq --arg name "$DATASET_NAME" 'all(.[]; .name != $name)' "dataset cleanup completed"

echo "Release 0.3 API E2E passed."
