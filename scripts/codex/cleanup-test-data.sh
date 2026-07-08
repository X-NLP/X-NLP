#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8080}"
APPLY="false"
PREFIX_REGEX='^(ui-test-|ui-reg-|codex-test-|debug-)'

if [[ "${1:-}" == "--apply" ]]; then
  APPLY="true"
elif [[ "${1:-}" != "" ]]; then
  echo "Usage: $0 [--apply]" >&2
  exit 2
fi

need_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

need_tool curl
need_tool jq

fetch_list() {
  local label="$1"
  local list_path="$2"

  echo "Checking ${label} from ${BASE_URL}${list_path}" >&2
  if ! curl -fsS "${BASE_URL}${list_path}"; then
    echo "Failed to fetch ${label} list from ${BASE_URL}${list_path}. Is the backend running and returning 2xx?" >&2
    exit 1
  fi
}

delete_resource() {
  local label="$1"
  local name="$2"
  local url="$3"

  echo "DELETE ${label}: ${name}"
  local status
  status="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "$url" || true)"

  if [[ "$status" =~ ^2 ]]; then
    echo "DELETE ok ${label}: ${name} (${status})"
  else
    echo "DELETE failed ${label}: ${name} (${status}) ${url}" >&2
    exit 1
  fi
}

cleanup_models() {
  local payload
  payload="$(fetch_list "model" "/api/v1/models")"

  echo "$payload" | jq -r '.[]? | [.name] | @tsv' | while IFS=$'\t' read -r name; do
    if [[ "$name" =~ $PREFIX_REGEX ]]; then
      if [[ -z "$name" ]]; then
        echo "SKIP model: missing name"
      elif [[ "$APPLY" == "true" ]]; then
        delete_resource "model" "$name" "${BASE_URL}/api/v1/models/${name}"
      else
        echo "DRY-RUN model: ${name}"
      fi
    fi
  done
}

cleanup_datasets() {
  local payload
  payload="$(fetch_list "dataset" "/api/v1/datasets")"

  echo "$payload" | jq -r '.[]? | [.id, (.name // .displayName // "")] | @tsv' | while IFS=$'\t' read -r id name; do
    if [[ "$name" =~ $PREFIX_REGEX ]]; then
      if [[ -z "$id" ]]; then
        echo "SKIP dataset: ${name} missing id"
      elif [[ "$APPLY" == "true" ]]; then
        delete_resource "dataset" "$name" "${BASE_URL}/api/v1/datasets/${id}"
      else
        echo "DRY-RUN dataset: ${name} (${id})"
      fi
    fi
  done
}

if [[ "$APPLY" == "true" ]]; then
  echo "Apply mode: matching test data will be deleted. Base: ${BASE_URL}"
else
  echo "Dry-run mode: pass --apply to delete matching test data. Base: ${BASE_URL}"
fi

cleanup_models
cleanup_datasets
