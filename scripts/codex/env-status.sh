#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

echo "== Git =="
git branch --show-current || true
git status --short || true

echo
echo "== Ports =="
if command -v lsof >/dev/null 2>&1; then
  lsof -i :5173 -sTCP:LISTEN || echo "Port 5173 is not listening"
  lsof -i :8080 -sTCP:LISTEN || echo "Port 8080 is not listening"
else
  echo "lsof is not available"
fi

echo
echo "== Startup hints =="
echo "Frontend: npm run dev --prefix xnlp-frontend"
echo "Backend:  cd xnlp-server && mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080"

echo
echo "== Tool versions =="
java -version 2>&1 | head -n 1 || true
mvn -version 2>/dev/null | head -n 1 || true
node --version || true
npm --version || true
