#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# run-profile creates an isolated temporary H2 file database and removes it on exit.
exec tests/e2e/run-profile.sh h2
