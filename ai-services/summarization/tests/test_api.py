from uuid import uuid4

from fastapi.testclient import TestClient

from portal_common.testing import HashingEmbedder
from summarization.engine import Summarizer
from summarization.main import build_app


def test_process_returns_verbatim_summary():
    text = "Hello there. I need my test results from last week. Could someone call me back about them? Thank you."
    with TestClient(build_app(lambda: Summarizer(HashingEmbedder()))) as client:
        body = client.post("/process", json={"message_id": str(uuid4()), "text": text}).json()
    assert body["summary"]
    for s in body["sentences"]:
        assert text[s["start"] : s["end"]] == s["text"]
    assert body["method"].startswith("extractive")
