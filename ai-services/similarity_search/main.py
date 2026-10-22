import time
from collections.abc import Callable
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from portal_common.embeddings import MODEL_ID
from portal_common.schemas import MAX_TEXT_CHARS, ProcessRequest
from portal_common.service import configure_logging, create_app, elapsed_ms

from .engine import load_engine
from .store import MessageNotFound

configure_logging()

VERSION = "0.2.0"
MAX_K = 20


class IndexRequest(ProcessRequest):
    k: int = Field(default=5, ge=1, le=MAX_K, description="Number of neighbours to return.")
    store: bool = Field(default=True, description="Store this message's embedding before searching.")


class SearchRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)
    k: int = Field(default=5, ge=1, le=MAX_K)


class NeighborOut(BaseModel):
    message_id: UUID
    score: float = Field(description="Cosine similarity, -1 to 1.")


class SearchResult(BaseModel):
    message_id: UUID | None = None
    neighbors: list[NeighborOut] = Field(description="Message ids and scores only; no text leaves this service.")
    stored: bool
    latency_ms: int


DEFAULT_K = 5


def kafka_handler(engine):
    def handle(message_id: UUID, text: str) -> dict:
        started = time.perf_counter()
        neighbors = engine.index_and_search(message_id, text, DEFAULT_K, True)
        return SearchResult(
            message_id=message_id,
            neighbors=[NeighborOut(**vars(n)) for n in neighbors],
            stored=True,
            latency_ms=elapsed_ms(started),
        ).model_dump(mode="json")

    return handle


def _checks(engine) -> dict[str, str]:
    return {"database": "ok" if engine.store.ping() else "unavailable"}


def build_app(engine_loader: Callable = load_engine) -> FastAPI:
    app = create_app(
        service="similarity-search",
        title="Similar Message Search Service",
        description=(
            f"Embeds redacted messages with `{MODEL_ID}` (384 dimensions), stores them in PostgreSQL "
            "pgvector, and returns the most similar earlier messages by cosine similarity. "
            "Responses contain message ids and scores only."
        ),
        version=VERSION,
        load_engine=engine_loader,
        health_checks=_checks,
        kafka_service="search",
        kafka_handler=kafka_handler,
    )

    @app.post("/process", response_model=SearchResult, tags=["search"], summary="Index a message and find similar ones")
    def process(req: IndexRequest, request: Request) -> SearchResult:
        started = time.perf_counter()
        try:
            neighbors = request.app.state.engine.index_and_search(req.message_id, req.text, req.k, req.store)
        except MessageNotFound:
            raise HTTPException(status_code=409, detail="message_id does not exist in the platform database")
        return SearchResult(
            message_id=req.message_id,
            neighbors=[NeighborOut(**vars(n)) for n in neighbors],
            stored=req.store,
            latency_ms=elapsed_ms(started),
        )

    @app.post("/search", response_model=SearchResult, tags=["search"], summary="Find messages similar to free text")
    def search(req: SearchRequest, request: Request) -> SearchResult:
        started = time.perf_counter()
        neighbors = request.app.state.engine.search(req.text, req.k)
        return SearchResult(neighbors=[NeighborOut(**vars(n)) for n in neighbors], stored=False, latency_ms=elapsed_ms(started))

    return app


app = build_app()
