#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8760}"
PROVIDER_BASE="${XNLP_PROVIDER_BASE:-http://127.0.0.1:18880}"
RUN_ID="${XNLP_E2E_RUN_ID:-$(date +%s)-$$}"
KB_NAME="codex-rag-${RUN_ID}"
RERANKER_NAME="codex-reranker-${RUN_ID}"
REQUEST_ID="codex-rag-request-${RUN_ID}"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xnlp-rag-e2e.XXXXXX")"
KB_ID=""
RERANKER_CREATED=false
AUTH_ARGS=(-H "X-Tenant-ID: ${XNLP_TENANT_ID:-default}")

if [[ -n "${XNLP_API_KEY:-}" ]]; then
  AUTH_ARGS+=(-H "${XNLP_SECURITY_HEADER:-X-API-Key}: ${XNLP_API_KEY}")
fi

cleanup() {
  set +e
  if [[ -n "$KB_ID" ]]; then
    curl -sS -o /dev/null -X DELETE "${AUTH_ARGS[@]}" \
      "${BASE_URL}/api/v1/knowledge-bases/${KB_ID}?force=true"
  fi
  if [[ "$RERANKER_CREATED" == true ]]; then
    curl -sS -o /dev/null -X DELETE "${AUTH_ARGS[@]}" \
      "${BASE_URL}/api/v1/models/${RERANKER_NAME}"
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required command: $tool" >&2; exit 2; }
done

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

wait_document_indexed() {
  local document_id="$1"
  for _ in $(seq 1 120); do
    request GET "/api/v1/knowledge-bases/${KB_ID}/documents/${document_id}" 200
    local status
    status="$(jq -r '.indexStatus' <<<"$LAST_BODY")"
    if [[ "$status" == "INDEXED" ]]; then
      return 0
    fi
    if [[ "$status" == "FAILED" ]]; then
      echo "Document indexing failed: ${document_id}" >&2
      jq . <<<"$LAST_BODY" >&2
      exit 1
    fi
    sleep 0.25
  done
  echo "Document indexing timed out: ${document_id}" >&2
  exit 1
}

echo "X-NLP Release 0.4 retrieval evaluation E2E"
echo "Base URL: ${BASE_URL}"

RERANKER_PAYLOAD="$(jq -nc \
  --arg name "$RERANKER_NAME" \
  --arg base "$PROVIDER_BASE" \
  '{
    name: $name,
    type: "RERANKING",
    protocol: "JINA_RERANK",
    source: "CUSTOM",
    provider: "xnlp-e2e",
    model_name: "xnlp-e2e-reranker",
    base_url: $base,
    api_key: "xnlp-e2e-placeholder",
    version: "e2e",
    backend: "http",
    device: "cpu"
  }')"
request POST /api/v1/models 201 "$RERANKER_PAYLOAD"
RERANKER_CREATED=true
assert_jq --arg name "$RERANKER_NAME" '.name == $name and .type == "RERANKING" and .apiKeySet == true' \
  "reranker profile is configured without credential exposure"

KB_PAYLOAD="$(jq -nc --arg name "$KB_NAME" '{
  name: $name,
  description: "Release 0.4 deterministic retrieval evaluation",
  embeddingModel: "xnlp-e2e-embedding",
  chunkPolicy: {maxCharacters: 1000, overlapCharacters: 0, separatorMode: "PARAGRAPH"}
}')"
request POST /api/v1/knowledge-bases 201 "$KB_PAYLOAD"
KB_ID="$(jq -r '.id' <<<"$LAST_BODY")"
assert_jq --arg name "$KB_NAME" '.name == $name and .embeddingModel == "xnlp-e2e-embedding"' \
  "knowledge base uses deterministic embedding model"

DOC_ONE="$(jq -nc '{
  title: "Portable database guide",
  content: "Spring applications can switch database providers through DataSource and JdbcTemplate configuration.",
  sourceType: "TEXT",
  externalId: "release-04-db",
  metadata: {topic: "database"}
}')"
request POST "/api/v1/knowledge-bases/${KB_ID}/documents" 202 "$DOC_ONE"
DOC_ONE_ID="$(jq -r '.id' <<<"$LAST_BODY")"
wait_document_indexed "$DOC_ONE_ID"

DOC_TWO="$(jq -nc '{
  title: "Retrieval reranking guide",
  content: "Retrieval evaluation compares raw vector ranking with deterministic rerank output and persists evidence.",
  sourceType: "TEXT",
  externalId: "release-04-rerank",
  metadata: {topic: "retrieval"}
}')"
request POST "/api/v1/knowledge-bases/${KB_ID}/documents" 202 "$DOC_TWO"
DOC_TWO_ID="$(jq -r '.id' <<<"$LAST_BODY")"
wait_document_indexed "$DOC_TWO_ID"

SEARCH_PAYLOAD='{"query":"database retrieval rerank","topK":2,"rerank":false,"rerankTopN":2}'
request POST "/api/v1/knowledge-bases/${KB_ID}/search" 200 "$SEARCH_PAYLOAD"
assert_jq '.matches | length == 2' "raw retrieval returns two deterministic chunks"
RAW_FIRST="$(jq -r '.matches[0].chunkId' <<<"$LAST_BODY")"
RELEVANT_CHUNK="$(jq -r '.matches[1].chunkId' <<<"$LAST_BODY")"

EVALUATION_PAYLOAD="$(jq -nc --arg relevant "$RELEVANT_CHUNK" '{
  samples: [
    {id: "rerank-hit", query: "database retrieval rerank", relevantChunkIds: [$relevant]},
    {id: "known-miss", query: "database retrieval rerank", relevantChunkIds: ["missing-chunk"]}
  ],
  topK: 2,
  rerank: true,
  rerankTopN: 2
}')"
request POST "/api/v1/knowledge-bases/${KB_ID}/retrieval-evaluations" 202 "$EVALUATION_PAYLOAD"
EVALUATION_ID="$(jq -r '.id' <<<"$LAST_BODY")"
assert_jq '.totalSamples == 2 and .topK == 2 and .rerank == true' "evaluation run accepted"

for _ in $(seq 1 200); do
  request GET "/api/v1/knowledge-bases/${KB_ID}/retrieval-evaluations/${EVALUATION_ID}" 200
  EVALUATION_STATUS="$(jq -r '.status' <<<"$LAST_BODY")"
  if [[ "$EVALUATION_STATUS" == "COMPLETED" ]]; then
    break
  fi
  if [[ "$EVALUATION_STATUS" == "FAILED" ]]; then
    echo "Retrieval evaluation failed" >&2
    jq . <<<"$LAST_BODY" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ "$EVALUATION_STATUS" != "COMPLETED" ]]; then
  echo "Retrieval evaluation timed out" >&2
  exit 1
fi
assert_jq '
  .processedSamples == 2 and
  .metrics.recallAtK == 0.5 and
  .metrics.mrr == 0.5 and
  .metrics.ndcgAtK == 0.5 and
  .metrics.averageLatencyMs >= 0 and
  .metrics.p95LatencyMs >= 0
' "aggregate Recall@K, MRR, nDCG and latency are persisted"

request GET "/api/v1/knowledge-bases/${KB_ID}/retrieval-evaluations/${EVALUATION_ID}/samples" 200
assert_jq --arg rawFirst "$RAW_FIRST" --arg relevant "$RELEVANT_CHUNK" '
  length == 2 and
  .[0].sampleId == "rerank-hit" and
  .[0].miss == false and
  .[0].rerankChanged == true and
  .[0].rawMatches[0].chunkId == $rawFirst and
  .[0].finalMatches[0].chunkId == $relevant and
  (.[0].finalMatches[0].rerankScore | type) == "number" and
  .[1].sampleId == "known-miss" and
  .[1].miss == true and
  .[1].recallAtK == 0
' "sample evidence identifies scores, miss and rerank changes"

request GET "/api/v1/knowledge-bases/${KB_ID}/retrieval-evaluations" 200
assert_jq --arg id "$EVALUATION_ID" 'any(.[]; .id == $id and .status == "COMPLETED")' \
  "evaluation list reads the persisted run"

echo "Release 0.4 retrieval evaluation E2E passed."
