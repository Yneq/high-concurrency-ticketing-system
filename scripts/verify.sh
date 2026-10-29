#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p load-test/results
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--load-only" ]; }; then
  echo "Usage: bash scripts/verify.sh [--load-only]" >&2
  exit 2
fi
if [ "${1:-}" = "--load-only" ]; then
  python3 scripts/check-java-evidence.py
else
  mvn -B -f verification/core/pom.xml test | tee load-test/results/core-tests.log
  mvn -B verify | tee load-test/results/maven.log
fi
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180 db redis kafka app
api_binding="$(docker compose port app 8080)"
if [ -z "$api_binding" ]; then
  echo "Compose app HTTP port is not published" >&2
  exit 1
fi
for i in {1..60}; do
  if curl -fsS "http://$api_binding/actuator/health/readiness" >/dev/null; then
    echo "API ready. Run load-test/final.js on a fresh final event."
    exit 0
  fi
  sleep 2
done
echo "API readiness timed out" >&2
exit 1
