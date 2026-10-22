"""Zero-shot message routing with an NLI model (DeBERTa-v3-base).

Each category is scored independently (``multi_label=True``): the model rates whether the message
entails "This patient message is about <description>." The best-scoring category wins if it clears
``OTHER_THRESHOLD``; otherwise the message is routed to "other". Treating "other" as a threshold
rather than a candidate label works better for NLI models, which struggle to entail a vague
hypothesis like "something else".
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

from portal_common.schemas import ModelInfo

MODEL_ID = "MoritzLaurer/deberta-v3-base-zeroshot-v2.0"
MODEL_REVISION = "8e7e5af5983a0ddb1a5b45a38b129ab69e2258e8"
MODEL_DIR = os.environ.get("ROUTING_MODEL_DIR", "/models/deberta-v3-base-zeroshot-v2.0")

HYPOTHESIS_TEMPLATE = "This patient message is about {}."
CATEGORY_DESCRIPTIONS = {
    "medication_refill": "a prescription or medication refill",
    "scheduling": "scheduling, rescheduling, or canceling an appointment",
    "test_results": "lab work or test results",
    "billing": "a bill, insurance, or a payment",
    "symptoms": "symptoms or a health problem",
}
CATEGORIES = [*CATEGORY_DESCRIPTIONS, "other"]
OTHER_THRESHOLD = 0.5
MAX_TOKENS = 512


# Measured optimum (scripts/routing_thread_sweep.py, 10 vCPUs): 2 threads per replica was fastest for
# both 1 and 3 replicas; 10 threads in one replica was 35% slower than 2 (oversubscription, and DeBERTa's
# small batches don't parallelise that far).
DEFAULT_THREAD_CAP = 2


def torch_threads() -> int:
    """Intra-op threads for this replica.

    ``TORCH_THREADS=auto`` (the routing image default) splits the container's vCPUs evenly across
    ``ROUTING_REPLICAS`` replicas, capped at ``TORCH_THREADS_CAP`` (default 2), so scaling out never
    oversubscribes the CPU and a lone replica doesn't over-thread. An integer pins it.
    """
    setting = os.environ.get("TORCH_THREADS", "auto")
    if setting != "auto":
        return max(1, int(setting))
    replicas = max(1, int(os.environ.get("ROUTING_REPLICAS", "1")))
    cap = max(1, int(os.environ.get("TORCH_THREADS_CAP", str(DEFAULT_THREAD_CAP))))
    return max(1, min(cap, (os.cpu_count() or 2) // replicas))


@dataclass(frozen=True)
class Routing:
    category: str
    confidence: float
    scores: dict[str, float]


def decide(scores: dict[str, float], threshold: float = OTHER_THRESHOLD) -> Routing:
    """``scores`` maps each category (not "other") to an independent entailment probability."""
    best = max(scores, key=scores.get)
    rounded = {k: round(v, 4) for k, v in scores.items()}
    if scores[best] < threshold:
        return Routing("other", round(1 - scores[best], 4), rounded)
    return Routing(best, round(scores[best], 4), rounded)


class RoutingEngine:
    def __init__(self, classifier: Any, threads: int | None = None):
        self._classifier = classifier
        self._threads = threads
        self._label_to_category = {v: k for k, v in CATEGORY_DESCRIPTIONS.items()}

    def model_info(self) -> ModelInfo:
        threads = f", torch threads {self._threads}" if self._threads else ""
        return ModelInfo(name=MODEL_ID, revision=MODEL_REVISION, detail=f"zero-shot NLI, other < {OTHER_THRESHOLD}{threads}")

    def route(self, text: str) -> Routing:
        out = self._classifier(
            text,
            candidate_labels=list(CATEGORY_DESCRIPTIONS.values()),
            hypothesis_template=HYPOTHESIS_TEMPLATE,
            multi_label=True,
        )
        scores = {self._label_to_category[label]: float(s) for label, s in zip(out["labels"], out["scores"])}
        return decide(scores)


def load_engine() -> RoutingEngine:
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer, pipeline

    threads = torch_threads()
    torch.set_num_threads(threads)
    torch.set_num_interop_threads(1)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, model_max_length=MAX_TOKENS)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_DIR)
    model.eval()
    classifier = pipeline("zero-shot-classification", model=model, tokenizer=tokenizer, device=-1)
    engine = RoutingEngine(classifier, threads)
    engine.route("Warm-up: I need a refill.")
    return engine
