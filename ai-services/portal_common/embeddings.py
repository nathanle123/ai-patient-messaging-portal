"""all-MiniLM-L6-v2 sentence embeddings, shared by summarization and similarity search."""

from __future__ import annotations

import os
from typing import Protocol

import numpy as np

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
MODEL_DIR = os.environ.get("MINILM_MODEL_DIR", "/models/all-MiniLM-L6-v2")
DIMENSIONS = 384


class Embedder(Protocol):
    def encode(self, texts: list[str]) -> np.ndarray:
        """Returns an (n, d) float32 array of L2-normalised embeddings."""
        ...


class MiniLmEmbedder:
    def __init__(self, model):
        self._model = model

    def encode(self, texts: list[str]) -> np.ndarray:
        return self._model.encode(texts, batch_size=32, normalize_embeddings=True, convert_to_numpy=True).astype(
            np.float32
        )


def load_minilm() -> MiniLmEmbedder:
    import torch
    from sentence_transformers import SentenceTransformer

    torch.set_num_threads(int(os.environ.get("TORCH_THREADS", "2")))
    embedder = MiniLmEmbedder(SentenceTransformer(MODEL_DIR, device="cpu"))
    embedder.encode(["warm-up"])
    return embedder
