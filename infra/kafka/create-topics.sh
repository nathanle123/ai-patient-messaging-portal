#!/bin/bash
# Creates every topic once; idempotent. Run by the kafka-init Compose service.
set -euo pipefail
BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
PARTITIONS="${PARTITIONS:-3}"
TOPICS=(
  messages.redacted
  results.urgency results.routing results.summarization results.search
  service.events
  service.events.dlq
  # Dead-letter topics keep the source topic's partition count, so a failed record can go to the
  # same partition number it came from.
  messages.redacted.dlq
  results.urgency.dlq results.routing.dlq results.summarization.dlq results.search.dlq
)
for topic in "${TOPICS[@]}"; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$PARTITIONS" --replication-factor 1
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
