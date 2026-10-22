from uuid import uuid4

from fastapi.testclient import TestClient

from portal_common.schemas import ModelInfo
from urgency.main import build_app
from urgency.rules import SOON, URGENT, Flag, decide


class FakeEngine:
    def model_info(self):
        return ModelInfo(name="fake-rules")

    def classify(self, text):
        flags = [Flag("chest pain", URGENT, "no chest pain" in text, 0, 10), Flag("fever", SOON, False, 12, 17)]
        return decide(flags), flags


def post(text):
    with TestClient(build_app(FakeEngine)) as client:
        return client.post("/process", json={"message_id": str(uuid4()), "text": text}).json()


def test_response_carries_label_flags_and_disclaimer():
    body = post("chest pain, fever")
    assert body["label"] == "urgent"
    assert [f["phrase"] for f in body["flags"]] == ["chest pain", "fever"]
    assert "not validated clinical triage" in body["disclaimer"].lower()


def test_negated_flag_is_reported_but_not_counted():
    body = post("no chest pain, fever")
    assert body["label"] == "soon"
    assert body["flags"][0]["negated"] is True
