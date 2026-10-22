"""Kafka consumer loop shared by the four downstream AI services.

Consumes ``messages.redacted`` in the service's own consumer group and, for each record:

1. parses ``{"message_id", "redacted_text", ...}``. A malformed record goes straight to the DLQ.
2. runs the service handler, retrying with exponential backoff (``MAX_ATTEMPTS`` tries).
3. on success, publishes ``results.<service>`` (keyed by message_id) and a SUCCESS service event;
   on final failure, publishes the record unchanged to ``messages.redacted.dlq`` with headers
   naming the service and error type, plus a FAILED service event.
4. waits for the broker to acknowledge (``flush``) and only then commits the offset.

A crash between steps 3 and 4 means redelivery and a duplicate result. The platform's
``(message_id, service_name)`` unique constraint makes that harmless (at-least-once + idempotent).
Records only ever contain redacted text; DLQ headers never include exception messages.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

log = logging.getLogger("portal.worker")

INPUT_TOPIC = "messages.redacted"
DLQ_TOPIC = "messages.redacted.dlq"
EVENTS_TOPIC = "service.events"
MAX_ATTEMPTS = 3
BASE_BACKOFF_S = 0.5
FLUSH_TIMEOUT_S = 10.0


class PermanentError(Exception):
    """Not worth retrying (bad input)."""


Handler = Callable[[uuid.UUID, str], dict[str, Any]]


@dataclass
class WorkerStats:
    processed: int = 0
    failed: int = 0
    last_poll: float = 0.0


def now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def service_event(message_id: str | None, service: str, status: str, latency_ms: int) -> dict[str, Any]:
    return {
        "event_id": str(uuid.uuid4()),
        "message_id": message_id,
        "service": service,
        "status": status,
        "latency_ms": latency_ms,
        "timestamp": now_iso(),
    }


def parse_record(value: bytes | None) -> tuple[uuid.UUID, str]:
    try:
        payload = json.loads(value or b"")
        message_id = uuid.UUID(payload["message_id"])
        text = payload["redacted_text"]
    except (ValueError, KeyError, TypeError) as e:
        raise PermanentError(type(e).__name__) from e
    if not isinstance(text, str) or not text.strip():
        raise PermanentError("empty redacted_text")
    return message_id, text


class KafkaWorker(threading.Thread):
    def __init__(
        self,
        *,
        service: str,
        result_topic: str,
        handler: Handler,
        consumer: Any,
        producer: Any,
        model_version: str | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        super().__init__(name=f"{service}-kafka-worker", daemon=True)
        self.service = service
        self.result_topic = result_topic
        self.handler = handler
        self.consumer = consumer
        self.producer = producer
        self.model_version = model_version
        self._sleep = sleep
        self._stop = threading.Event()
        self.stats = WorkerStats()

    # ---- lifecycle -------------------------------------------------------------------------
    def run(self) -> None:
        self.consumer.subscribe([INPUT_TOPIC])
        log.info("%s consuming %s", self.service, INPUT_TOPIC)
        try:
            while not self._stop.is_set():
                self.poll_once()
        finally:
            self.consumer.close()

    def stop(self) -> None:
        self._stop.set()

    def healthy(self) -> bool:
        return self.is_alive() and time.monotonic() - self.stats.last_poll < 30

    # ---- one record --------------------------------------------------------------------------
    def poll_once(self, timeout: float = 1.0) -> None:
        self.stats.last_poll = time.monotonic()
        msg = self.consumer.poll(timeout)
        if msg is None:
            return
        if msg.error():
            log.warning("%s consumer error: %s", self.service, msg.error().name())
            return
        self.process(msg)
        self.consumer.commit(message=msg, asynchronous=False)

    def process(self, msg: Any) -> None:
        started = time.perf_counter()
        message_id: str | None = None
        try:
            mid, text = parse_record(msg.value())
            message_id = str(mid)
            result = self._with_retries(mid, text)
        except Exception as e:  # noqa: BLE001 - every failure is routed to the DLQ
            attempts = 1 if isinstance(e, PermanentError) else MAX_ATTEMPTS
            self._dead_letter(msg, message_id, type(e).__name__, attempts, started)
            return
        latency = round((time.perf_counter() - started) * 1000)
        envelope = {
            "message_id": message_id,
            "service": self.service,
            "model_version": self.model_version,
            "processed_at": now_iso(),
            "result": result,
        }
        self._produce(self.result_topic, message_id, envelope)
        self._produce(EVENTS_TOPIC, message_id, service_event(message_id, self.service, "SUCCESS", latency))
        self._flush()
        self.stats.processed += 1

    def _with_retries(self, message_id: uuid.UUID, text: str) -> dict[str, Any]:
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                return self.handler(message_id, text)
            except PermanentError:
                raise
            except Exception as e:  # noqa: BLE001
                if attempt == MAX_ATTEMPTS:
                    raise
                delay = BASE_BACKOFF_S * 2 ** (attempt - 1)
                log.warning("%s attempt %d failed for %s (%s); retrying in %.1fs",
                            self.service, attempt, message_id, type(e).__name__, delay)
                self._sleep(delay)
        raise AssertionError("unreachable")

    def _dead_letter(self, msg: Any, message_id: str | None, error: str, attempts: int, started: float) -> None:
        log.error("%s sending %s to %s after %d attempt(s): %s", self.service, message_id, DLQ_TOPIC, attempts, error)
        headers = [
            ("dlq.service", self.service.encode()),
            ("dlq.error", error.encode()),
            ("dlq.attempts", str(attempts).encode()),
            ("dlq.source", f"{msg.topic()}/{msg.partition()}/{msg.offset()}".encode()),
        ]
        self.producer.produce(DLQ_TOPIC, key=msg.key(), value=msg.value(), headers=headers)
        latency = round((time.perf_counter() - started) * 1000)
        self._produce(EVENTS_TOPIC, message_id, service_event(message_id, self.service, "FAILED", latency))
        self._flush()
        self.stats.failed += 1

    def _produce(self, topic: str, key: str | None, value: dict[str, Any]) -> None:
        self.producer.produce(topic, key=key.encode() if key else None, value=json.dumps(value).encode())

    def _flush(self) -> None:
        remaining = self.producer.flush(FLUSH_TIMEOUT_S)
        if remaining:
            # Not acknowledged: do not commit. Raising stops this record's commit; the thread
            # exits, /health goes 503, and the record is redelivered after restart.
            raise RuntimeError(f"{remaining} record(s) not acknowledged by Kafka")


def start_worker(service: str, handler: Handler, bootstrap: str, model_version: str | None = None) -> KafkaWorker:
    from confluent_kafka import Consumer, Producer

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": service,
            "client.id": f"{service}-{uuid.uuid4().hex[:6]}",
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
            # Routing can take seconds per record; leave plenty of room before a rebalance.
            "max.poll.interval.ms": 300_000,
        }
    )
    producer = Producer(
        {"bootstrap.servers": bootstrap, "acks": "all", "enable.idempotence": True, "linger.ms": 5}
    )
    worker = KafkaWorker(service=service, result_topic=f"results.{service}", handler=handler,
                         consumer=consumer, producer=producer, model_version=model_version)
    worker.start()
    return worker
