"""Deterministic stand-in for MiniLM in unit tests: hashed bag-of-words, L2-normalised."""

import hashlib
import re

import numpy as np


class HashingEmbedder:
    def __init__(self, dimensions: int = 384):
        self.dimensions = dimensions

    def encode(self, texts: list[str]) -> np.ndarray:
        out = np.zeros((len(texts), self.dimensions), dtype=np.float32)
        for row, text in enumerate(texts):
            for word in re.findall(r"[a-z0-9']+", text.lower()):
                out[row, int(hashlib.md5(word.encode()).hexdigest(), 16) % self.dimensions] += 1.0
            norm = np.linalg.norm(out[row])
            if norm:
                out[row] /= norm
        return out
