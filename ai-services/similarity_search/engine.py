from __future__ import annotations

import os
from dataclasses import dataclass
from uuid import UUID

from portal_common.embeddings import MODEL_ID, MODEL_REVISION, Embedder
from portal_common.schemas import ModelInfo

from .store import VectorStore

MODEL_VERSION = f"{MODEL_ID}@{MODEL_REVISION[:12]}"


@dataclass(frozen=True)
class Neighbor:
    message_id: UUID
    score: float


class SearchEngine:
    def __init__(self, embedder: Embedder, store: VectorStore):
        self._embedder = embedder
        self.store = store

    def model_info(self) -> ModelInfo:
        return ModelInfo(name=MODEL_ID, revision=MODEL_REVISION, detail="cosine similarity over pgvector (HNSW)")

    def index_and_search(self, message_id: UUID, text: str, k: int, store: bool) -> list[Neighbor]:
        vector = self._embedder.encode([text])[0]
        if store:
            self.store.upsert(message_id, vector, MODEL_VERSION)
        return [Neighbor(mid, round(score, 4)) for mid, score in self.store.nearest(vector, k, exclude=message_id)]

    def search(self, text: str, k: int) -> list[Neighbor]:
        vector = self._embedder.encode([text])[0]
        return [Neighbor(mid, round(score, 4)) for mid, score in self.store.nearest(vector, k)]

    def close(self) -> None:
        close = getattr(self.store, "close", None)
        if callable(close):
            close()


def load_engine() -> SearchEngine:
    from portal_common.embeddings import load_minilm

    from .store import PgVectorStore

    dsn = os.environ.get("SEARCH_DB_DSN")
    if not dsn:
        raise RuntimeError("SEARCH_DB_DSN is not set")
    return SearchEngine(load_minilm(), PgVectorStore(dsn))
