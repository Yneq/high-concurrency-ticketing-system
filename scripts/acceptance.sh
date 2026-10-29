#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify.sh "$@"
EVENT_ID="${EVENT_ID:-$(date +%s)}"
if ! [[ "$EVENT_ID" =~ ^[0-9]+$ ]] || [ "$EVENT_ID" = 1 ]; then
  echo "EVENT_ID must be a fresh positive numeric ID other than 1" >&2; exit 1
fi
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U ticketing -d ticketing \
  -c "INSERT INTO tickets(event_id,total_stock,remaining) VALUES($EVENT_ID,100,100);"
docker compose run --rm --user "$(id -u):$(id -g)" k6 run \
  -e "EVENT_ID=$EVENT_ID" -e "REQUESTS=${REQUESTS:-1000}" -e "VUS=${VUS:-100}" /scripts/final.js
cp load-test/results/summary.json "load-test/results/event-$EVENT_ID.json"
echo "Admission verification finished. Report: load-test/results/event-$EVENT_ID.json"
JOURNEY_EVENT_ID="$((EVENT_ID + 1))"
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U ticketing -d ticketing \
  -c "INSERT INTO tickets(event_id,total_stock,remaining) VALUES($JOURNEY_EVENT_ID,100,100);"
docker compose run --rm --user "$(id -u):$(id -g)" k6 run \
  -e "EVENT_ID=$JOURNEY_EVENT_ID" -e "REQUESTS=${REQUESTS:-1000}" -e "VUS=${VUS:-100}" /scripts/journey.js
cp load-test/results/journey-summary.json "load-test/results/journey-$JOURNEY_EVENT_ID.json"
echo "End-to-end journey verification finished. Report: load-test/results/journey-$JOURNEY_EVENT_ID.json"
