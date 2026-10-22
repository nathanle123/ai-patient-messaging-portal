from uuid import uuid4

from fastapi.testclient import TestClient

from portal_common.schemas import ModelInfo, ProcessRequest
from portal_common.service import create_app


class FakeEngine:
    def model_info(self) -> ModelInfo:
        return ModelInfo(name="fake", revision="0" * 40)


def make_app(checks=None):
    app = create_app(
        service="fake",
        title="Fake",
        description="test",
        version="0.0.0",
        load_engine=FakeEngine,
        health_checks=checks,
    )

    @app.post("/process")
    def process(req: ProcessRequest) -> dict:
        raise RuntimeError(f"boom: {req.text}")

    return app


def test_health_reports_model():
    with TestClient(make_app()) as client:
        body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["model"]["name"] == "fake"


def test_failing_check_makes_health_503():
    with TestClient(make_app(lambda _: {"database": "unavailable"})) as client:
        res = client.get("/health")
    assert res.status_code == 503
    assert res.json()["checks"] == {"database": "unavailable"}


def test_validation_error_does_not_echo_input():
    secret = "Maria Lopez DOB 1962-03-14 " * 500  # over the length limit
    with TestClient(make_app()) as client:
        res = client.post("/process", json={"message_id": str(uuid4()), "text": secret})
    assert res.status_code == 422
    assert "Maria" not in res.text
    assert "input" not in res.json()["detail"][0]


def test_unexpected_error_hides_exception_message():
    with TestClient(make_app(), raise_server_exceptions=False) as client:
        res = client.post("/process", json={"message_id": str(uuid4()), "text": "Maria Lopez"})
    assert res.status_code == 500
    assert "Maria" not in res.text
