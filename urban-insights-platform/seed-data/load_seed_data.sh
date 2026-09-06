#!/usr/bin/env bash
#
# Loads seed-data/*.json into a running instance of the platform
# (docker compose up first). Requires: curl, jq.
#
# Usage:
#   ./load_seed_data.sh
#   API_KEY=my-secret TRAFFIC_URL=http://localhost:8081 ./load_seed_data.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

API_KEY="${API_KEY:-${SECURITY_API_KEY:-change-me-in-prod}}"
TRAFFIC_URL="${TRAFFIC_URL:-http://localhost:8081}"
COMPLAINT_URL="${COMPLAINT_URL:-http://localhost:8082}"

READINGS_FILE="$SCRIPT_DIR/traffic_sensor_readings.json"
COMPLAINTS_FILE="$SCRIPT_DIR/citizen_complaints.json"

command -v jq >/dev/null 2>&1 || { echo "This script requires 'jq'. Install it and re-run." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "This script requires 'curl'. Install it and re-run." >&2; exit 1; }

if [ ! -f "$READINGS_FILE" ] || [ ! -f "$COMPLAINTS_FILE" ]; then
  echo "Seed data not found. Run 'python3 generate_seed_data.py' first (from this directory)." >&2
  exit 1
fi

echo "== Loading sensor readings into traffic-service ($TRAFFIC_URL) =="
# Uses /ingest-sync (not the async /ingest) so this script gets an immediate,
# clear pass/fail per reading rather than a blind 202 — appropriate for a
# one-shot seed load, not how you'd ingest a real sensor fleet.
count=0
fail=0
total=$(jq 'length' "$READINGS_FILE")
jq -c '.[]' "$READINGS_FILE" | while read -r reading; do
  count=$((count + 1))
  http_code=$(curl -s -o /tmp/seed_response.json -w "%{http_code}" \
    -X POST "$TRAFFIC_URL/api/traffic/ingest-sync" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d "$reading")
  if [ "$http_code" != "200" ]; then
    fail=$((fail + 1))
    echo "  [WARN] reading $count/$total failed (HTTP $http_code): $(cat /tmp/seed_response.json)"
  fi
  if [ $((count % 50)) -eq 0 ]; then
    echo "  ...$count/$total readings loaded"
  fi
done
echo "Sensor readings load complete."

echo ""
echo "== Loading citizen complaints into complaint-service ($COMPLAINT_URL) =="
count=0
total=$(jq 'length' "$COMPLAINTS_FILE")
jq -c '.[]' "$COMPLAINTS_FILE" | while read -r complaint; do
  count=$((count + 1))
  http_code=$(curl -s -o /tmp/seed_response.json -w "%{http_code}" \
    -X POST "$COMPLAINT_URL/api/complaints" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d "$complaint")
  if [ "$http_code" != "200" ]; then
    echo "  [WARN] complaint $count/$total failed (HTTP $http_code): $(cat /tmp/seed_response.json)"
  fi
  # Complaint submission is fast (heuristic classification, async AI enrichment
  # via Kafka) — no need to throttle, but a tiny delay keeps log output readable.
  sleep 0.05
done
echo "Citizen complaints load complete ($total submitted)."

echo ""
echo "Done. Note: complaint classification/duplicate-check happens asynchronously"
echo "via Kafka — give it a few seconds, then check:"
echo "  curl -H \"X-API-Key: $API_KEY\" $COMPLAINT_URL/api/complaints/needing-reclassification"
echo "(should shrink to 0 as ai-insight-service catches up)."

rm -f /tmp/seed_response.json
