"""Extractive summarisation: pick the 1-2 sentences most representative of the message.

Never generates text. Every summary sentence is an exact substring of the input, returned with its
character offsets.

1. Split into sentences (punctuation and line breaks; offsets preserved).
2. Drop greeting/sign-off fragments ("Hi Doc,", "Thanks,", "<PERSON>"): fewer than MIN_WORDS words.
3. Embed the candidates and the whole message with MiniLM. Relevance = cosine(sentence, message).
4. Select greedily with MMR (relevance minus redundancy with already-picked sentences).
   Messages with 1-2 candidate sentences get 1; longer ones get 2.
5. Return the picks in their original order.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

import numpy as np

from portal_common.embeddings import MODEL_ID, MODEL_REVISION, Embedder
from portal_common.schemas import ModelInfo

MIN_WORDS = 4
MMR_LAMBDA = 0.7
METHOD = "extractive: MiniLM centrality + MMR"

# A sentence ends at ., ! or ? followed by whitespace, or at a line break.
_BOUNDARY = re.compile(r"(?<=[.!?])\s+|\n+")
_WORD = re.compile(r"[A-Za-z0-9']+")


@dataclass(frozen=True)
class Sentence:
    index: int
    start: int
    end: int
    text: str


@dataclass(frozen=True)
class Summary:
    text: str
    selected: list[Sentence]
    scores: dict[int, float]
    candidate_count: int


def split_sentences(text: str) -> list[Sentence]:
    sentences, cursor = [], 0
    for match in [*_BOUNDARY.finditer(text), None]:
        end = match.start() if match else len(text)
        raw = text[cursor:end]
        stripped = raw.strip()
        if stripped:
            start = cursor + raw.index(stripped)
            sentences.append(Sentence(len(sentences), start, start + len(stripped), stripped))
        if match:
            cursor = match.end()
    return sentences


def word_count(sentence: str) -> int:
    # Redaction placeholders like <PERSON> are not words.
    return len(_WORD.findall(re.sub(r"<[A-Z_]+>", " ", sentence)))


def select(relevance: np.ndarray, similarity: np.ndarray, k: int, lam: float = MMR_LAMBDA) -> list[int]:
    chosen: list[int] = []
    remaining = list(range(len(relevance)))
    while remaining and len(chosen) < k:
        def mmr(i: int) -> float:
            redundancy = max((similarity[i, j] for j in chosen), default=0.0)
            return lam * relevance[i] - (1 - lam) * redundancy

        best = max(remaining, key=mmr)
        chosen.append(best)
        remaining.remove(best)
    return chosen


class Summarizer:
    def __init__(self, embedder: Embedder):
        self._embedder = embedder

    def model_info(self) -> ModelInfo:
        return ModelInfo(name=MODEL_ID, revision=MODEL_REVISION, detail=METHOD)

    def summarize(self, text: str) -> Summary:
        sentences = split_sentences(text)
        candidates = [s for s in sentences if word_count(s.text) >= MIN_WORDS] or sentences
        if not candidates:
            return Summary("", [], {}, 0)
        if len(candidates) == 1:
            return Summary(candidates[0].text, candidates, {candidates[0].index: 1.0}, 1)

        vectors = self._embedder.encode([text] + [s.text for s in candidates])
        message_vec, sentence_vecs = vectors[0], vectors[1:]
        relevance = sentence_vecs @ message_vec
        similarity = sentence_vecs @ sentence_vecs.T
        k = 1 if len(candidates) <= 2 else 2
        picked = sorted(select(relevance, similarity, k), key=lambda i: candidates[i].start)
        selected = [candidates[i] for i in picked]
        scores = {c.index: round(float(r), 4) for c, r in zip(candidates, relevance)}
        return Summary(" ".join(s.text for s in selected), selected, scores, len(candidates))


def load_engine() -> Summarizer:
    from portal_common.embeddings import load_minilm

    return Summarizer(load_minilm())
