import json
import uuid

import pytest

from portal_common.worker import DLQ_TOPIC, EVENTS_TOPIC, MAX_ATTEMPTS, KafkaWorker, parse_record, PermanentError


class FakeMsg:
    def __init__(self, value, key=b"k", offset=7):
        self._value, self._key, self._offset = value, key, offset

    def value(self): return self._value
    def key(self): return self._key
    def error(self): return None
    def topic(self): return "messages.redacted"
    def partition(self): return 1
    def offset(self): return self._offset


class FakeConsumer:
    def __init__(self, messages):
        self.messages, self.commits = list(messages), []

    def poll(self, timeout): return self.messages.pop(0) if self.messages else None
    def commit(self, message, asynchronous): self.commits.append(message.offset())


class FakeProducer:
    def __init__(self, unacked=0):
        self.records, self.flushes, self.unacked = [], 0, unacked

    def produce(self, topic, key=None, value=None, headers=None):
        self.records.append({"topic": topic, "key": key, "value": value, "headers": dict(headers or [])})

    def flush(self, timeout):
        self.flushes += 1
        return self.unacked


def record(text="I need a refill <PERSON>"):
    return json.dumps({"message_id": str(uuid.uuid4()), "redacted_text": text}).encode()


def worker(handler, messages, producer=None):
    return KafkaWorker(service="urgency", result_topic="results.urgency", handler=handler,
                       consumer=FakeConsumer(messages), producer=producer or FakeProducer(), sleep=lambda s: None)


def test_success_publishes_result_and_event_then_commits():
    w = worker(lambda mid, text: {"label": "routine"}, [FakeMsg(record())])
    w.poll_once()
    topics = [r["topic"] for r in w.producer.records]
    assert topics == ["results.urgency", EVENTS_TOPIC]
    envelope = json.loads(w.producer.records[0]["value"])
    assert envelope["service"] == "urgency" and envelope["result"] == {"label": "routine"}
    event = json.loads(w.producer.records[1]["value"])
    assert set(event) == {"event_id", "message_id", "service", "status", "latency_ms", "timestamp"}
    assert event["status"] == "SUCCESS"
    assert w.consumer.commits == [7]
    assert w.producer.flushes == 1  # flushed before commit


def test_transient_failure_is_retried():
    calls = []

    def flaky(mid, text):
        calls.append(1)
        if len(calls) < MAX_ATTEMPTS:
            raise TimeoutError()
        return {"ok": True}

    w = worker(flaky, [FakeMsg(record())])
    w.poll_once()
    assert len(calls) == MAX_ATTEMPTS
    assert w.producer.records[0]["topic"] == "results.urgency"


def test_persistent_failure_goes_to_dlq_with_safe_headers_and_is_committed():
    def broken(mid, text):
        raise ValueError("secret detail that must not leak")

    raw = record()
    w = worker(broken, [FakeMsg(raw)])
    w.poll_once()
    dlq = w.producer.records[0]
    assert dlq["topic"] == DLQ_TOPIC
    assert dlq["value"] == raw  # the redacted record, unchanged
    assert dlq["headers"]["dlq.error"] == b"ValueError"
    assert dlq["headers"]["dlq.attempts"] == str(MAX_ATTEMPTS).encode()
    assert b"secret detail" not in b"".join(dlq["headers"].values())
    assert json.loads(w.producer.records[1]["value"])["status"] == "FAILED"
    assert w.consumer.commits == [7]


def test_malformed_record_skips_retries():
    calls = []
    w = worker(lambda mid, text: calls.append(1), [FakeMsg(b"{not json")])
    w.poll_once()
    assert calls == []
    assert w.producer.records[0]["headers"]["dlq.attempts"] == b"1"
    assert w.consumer.commits == [7]


def test_unacknowledged_publish_does_not_commit():
    w = worker(lambda mid, text: {"ok": True}, [FakeMsg(record())], producer=FakeProducer(unacked=1))
    with pytest.raises(RuntimeError):
        w.poll_once()
    assert w.consumer.commits == []


@pytest.mark.parametrize("value", [b"", b"[]", json.dumps({"message_id": "nope", "redacted_text": "x"}).encode(),
                                   json.dumps({"message_id": str(uuid.uuid4()), "redacted_text": "  "}).encode()])
def test_parse_rejects_bad_records(value):
    with pytest.raises(PermanentError):
        parse_record(value)
