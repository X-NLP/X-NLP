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

delete_matches() {
  local label="$1"
  local list_path="$2"
  local delete_path_prefix="$3"

  echo "Checking ${label} from ${BASE_URL}${list_path}"
  local payload
  payload="$(curl -sS "${BASE_URL}${list_path}")"

  echo "$payload" | jq -r '.[]? | [.id, (.name // .displayName // "")] | @tsv' | while IFS=$'\t' read -r id name; do
    if [[ "$name" =~ $PREFIX_REGEX ]]; then
      if [[ "$APPLY" == "true" ]]; then
        echo "DELETE ${label}: ${name} (${id})"
        curl -sS -X DELETE "${BASE_URL}${delete_path_prefix}/${id}" >/dev/null
      else
        echo "DRY-RUN ${label}: ${name} (${id})"
      fi
    fi
  done
}

if [[ "$APPLY" == "true" ]]; then
  echo "Apply mode: matching test data will be deleted. Base: ${BASE_URL}"
else
  echo "Dry-run mode: pass --apply to delete matching test data. Base: ${BASE_URL}"
fi

delete_matches "model" "/api/v1/models" "/api/v1/models"
delete_matches "dataset" "/api/v1/datasets" "/api/v1/datasets"
