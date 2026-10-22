from uuid import uuid4

from fastapi.testclient import TestClient

from portal_common.testing import HashingEmbedder
from similarity_search.engine import SearchEngine
from similarity_search.main import build_app
from similarity_search.store import InMemoryStore


def client_with(store):
    return TestClient(build_app(lambda: SearchEngine(HashingEmbedder(), store)))


def test_neighbors_rank_by_similarity_and_exclude_self():
    store = InMemoryStore()
    ids = [uuid4() for _ in range(3)]
    texts = [
        "I need a refill of my blood pressure medication",
        "Please refill my blood pressure pills",
        "My bill from the last visit is wrong",
    ]
    with client_with(store) as client:
        for mid, text in zip(ids, texts):
            res = client.post("/process", json={"message_id": str(mid), "text": text, "k": 2})
            assert res.status_code == 200
        body = res.json()  # the billing message, searched against the two refills
        assert all(n["message_id"] != str(ids[2]) for n in body["neighbors"])

        again = client.post("/process", json={"message_id": str(ids[0]), "text": texts[0], "k": 2}).json()
    assert again["neighbors"][0]["message_id"] == str(ids[1])
    assert set(again) == {"message_id", "neighbors", "stored", "latency_ms"}  # ids and scores only
    assert len(store.rows) == 3  # re-indexing the same id is an upsert


def test_store_false_does_not_index():
    store = InMemoryStore()
    with client_with(store) as client:
        client.post("/process", json={"message_id": str(uuid4()), "text": "hello there", "store": False})
    assert store.rows == {}


def test_unknown_message_is_409():
    with client_with(InMemoryStore(known_message_ids=set())) as client:
        res = client.post("/process", json={"message_id": str(uuid4()), "text": "hello"})
    assert res.status_code == 409


def test_free_text_search():
    store = InMemoryStore()
    mid = uuid4()
    with client_with(store) as client:
        client.post("/process", json={"message_id": str(mid), "text": "question about my lab results"})
        body = client.post("/search", json={"text": "lab results question", "k": 1}).json()
    assert body["neighbors"][0]["message_id"] == str(mid)
    assert body["stored"] is False


def test_k_is_bounded():
    with client_with(InMemoryStore()) as client:
        res = client.post("/process", json={"message_id": str(uuid4()), "text": "x", "k": 500})
    assert res.status_code == 422
