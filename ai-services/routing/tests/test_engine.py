import pytest

from routing.engine import CATEGORIES, CATEGORY_DESCRIPTIONS, RoutingEngine, decide


def test_best_category_wins_above_threshold():
    r = decide({"medication_refill": 0.92, "symptoms": 0.40, "billing": 0.01})
    assert (r.category, r.confidence) == ("medication_refill", 0.92)


def test_low_scores_route_to_other():
    r = decide({"medication_refill": 0.21, "symptoms": 0.34, "billing": 0.05})
    assert r.category == "other"
    assert r.confidence == pytest.approx(0.66)
    assert r.scores["symptoms"] == 0.34


def test_categories_match_platform_schema():
    # Must match the CHECK constraint on message.category (platform V2 migration).
    assert CATEGORIES == ["medication_refill", "scheduling", "test_results", "billing", "symptoms", "other"]


def test_engine_maps_pipeline_labels_back_to_categories():
    def fake_pipeline(text, candidate_labels, hypothesis_template, multi_label):
        assert multi_label is True
        assert "{}" in hypothesis_template
        scores = [0.9 if "refill" in label else 0.1 for label in candidate_labels]
        return {"labels": candidate_labels, "scores": scores}

    r = RoutingEngine(fake_pipeline).route("refill please")
    assert r.category == "medication_refill"
    assert set(r.scores) == set(CATEGORY_DESCRIPTIONS)


def test_auto_threads_split_vcpus_across_replicas(monkeypatch):
    from routing.engine import torch_threads

    monkeypatch.setattr("os.cpu_count", lambda: 10)
    monkeypatch.setenv("TORCH_THREADS", "auto")
    monkeypatch.setenv("ROUTING_REPLICAS", "1")
    assert torch_threads() == 2  # capped: the measured optimum
    monkeypatch.setenv("ROUTING_REPLICAS", "3")
    assert torch_threads() == 2
    monkeypatch.setenv("ROUTING_REPLICAS", "6")
    assert torch_threads() == 1  # 10 vCPUs // 6
    monkeypatch.setenv("ROUTING_REPLICAS", "20")
    assert torch_threads() == 1
    monkeypatch.setenv("ROUTING_REPLICAS", "1")
    monkeypatch.setenv("TORCH_THREADS_CAP", "10")
    assert torch_threads() == 10  # uncapped: the naive vCPUs / replicas rule
    monkeypatch.setenv("TORCH_THREADS", "2")
    assert torch_threads() == 2
