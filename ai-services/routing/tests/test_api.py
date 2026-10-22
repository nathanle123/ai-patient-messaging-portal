from uuid import uuid4

from fastapi.testclient import TestClient

from portal_common.schemas import ModelInfo
from routing.engine import decide
from routing.main import build_app


class FakeEngine:
    def model_info(self):
        return ModelInfo(name="fake-nli")

    def route(self, text):
        return decide({"scheduling": 0.8, "symptoms": 0.2, "billing": 0.0, "test_results": 0.0, "medication_refill": 0.0})


def test_process_returns_category_and_scores():
    with TestClient(build_app(FakeEngine)) as client:
        body = client.post("/process", json={"message_id": str(uuid4()), "text": "move my appointment"}).json()
    assert body["category"] == "scheduling"
    assert body["confidence"] == 0.8
    assert len(body["scores"]) == 5
