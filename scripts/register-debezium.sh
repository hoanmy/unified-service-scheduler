#!/usr/bin/env bash
# =============================================================================
# Setup Script — Register Debezium CDC Connector
# Run AFTER: docker compose -f docker-compose.dev.yml up -d
# =============================================================================

set -euo pipefail

DEBEZIUM_URL="http://localhost:8083"
CONNECTOR_CONFIG="$(dirname "$0")/debezium-connector.json"

echo "⏳ Waiting for Debezium Connect to be ready..."
until curl -sf "$DEBEZIUM_URL/connectors" > /dev/null 2>&1; do
    echo "  Debezium not ready yet, retrying in 5s..."
    sleep 5
done
echo "✓ Debezium Connect is ready"

echo "📦 Registering outbox-event-connector..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$DEBEZIUM_URL/connectors" \
    -H "Content-Type: application/json" \
    -d @"$CONNECTOR_CONFIG")

if [ "$RESPONSE" = "201" ]; then
    echo "✓ Connector registered successfully"
elif [ "$RESPONSE" = "409" ]; then
    echo "ℹ Connector already exists. Updating..."
    curl -s -X PUT "$DEBEZIUM_URL/connectors/outbox-event-connector/config" \
        -H "Content-Type: application/json" \
        -d "$(cat "$CONNECTOR_CONFIG" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d['config']))")"
    echo "✓ Connector updated"
else
    echo "✗ Failed to register connector (HTTP $RESPONSE)"
    exit 1
fi

echo ""
echo "🔌 Connector Status:"
curl -s "$DEBEZIUM_URL/connectors/outbox-event-connector/status" | python3 -m json.tool 2>/dev/null

echo ""
echo "✅ CDC Pipeline Active:"
echo "   PostgreSQL WAL → Debezium → Kafka topic: appointment-events"
echo "   Consumer Group A (search-sync-worker) → Elasticsearch"
echo "   Consumer Group B (notification-worker) → Email/SMS/Push"
