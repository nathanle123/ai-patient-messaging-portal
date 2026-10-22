from uuid import uuid4

from fastapi.testclient import TestClient

from phi_detection.engine import Redaction, Span
from phi_detection.main import build_app
from portal_common.schemas import ModelInfo


class FakeEngine:
    def model_info(self):
        return ModelInfo(name="fake-presidio", revision="test")

    def redact(self, text):
        start = text.index("Maria")
        return Redaction(
            redacted_text=text.replace("Maria", "<PERSON>"),
            spans=[Span("PERSON", start, start + 5, 0.85)],
        )


def test_process_returns_redacted_text_and_offsets_only():
    message_id = str(uuid4())
    with TestClient(build_app(FakeEngine)) as client:
        res = client.post("/process", json={"message_id": message_id, "text": "Hi, this is Maria."})
    assert res.status_code == 200
    body = res.json()
    assert body["message_id"] == message_id
    assert body["redacted_text"] == "Hi, this is <PERSON>."
    assert body["entities"] == [{"entity_type": "PERSON", "start": 12, "end": 17, "score": 0.85}]
    assert body["entity_counts"] == {"PERSON": 1}
    assert body["phi_entity_count"] == 1
    assert "Maria" not in res.text


def test_empty_text_is_rejected():
    with TestClient(build_app(FakeEngine)) as client:
        res = client.post("/process", json={"message_id": str(uuid4()), "text": ""})
    assert res.status_code == 422


def test_openapi_is_published():
    with TestClient(build_app(FakeEngine)) as client:
        spec = client.get("/openapi.json").json()
    assert "/process" in spec["paths"] and "/health" in spec["paths"]
