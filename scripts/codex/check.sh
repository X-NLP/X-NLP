#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

run() {
  echo "+ $*"
  "$@"
}

run npm run build --prefix xnlp-frontend
run mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2
run mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2
