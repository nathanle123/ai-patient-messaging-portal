import time
from collections.abc import Callable
from uuid import UUID

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from portal_common.embeddings import MODEL_ID
from portal_common.schemas import ProcessRequest
from portal_common.service import configure_logging, create_app, elapsed_ms

from .engine import METHOD, load_engine

configure_logging()

VERSION = "0.2.0"


class SelectedSentence(BaseModel):
    index: int = Field(description="Position among all sentences in the message.")
    start: int
    end: int
    text: str


class SummaryResult(BaseModel):
    message_id: UUID
    summary: str = Field(description="Selected sentences verbatim, in original order. No generated text.")
    sentences: list[SelectedSentence]
    sentence_scores: dict[int, float] = Field(description="Cosine similarity of each candidate sentence to the message.")
    candidate_count: int
    method: str
    latency_ms: int


def handle(engine, message_id: UUID, text: str) -> SummaryResult:
    started = time.perf_counter()
    s = engine.summarize(text)
    return SummaryResult(
        message_id=message_id,
        summary=s.text,
        sentences=[SelectedSentence(**vars(x)) for x in s.selected],
        sentence_scores=s.scores,
        candidate_count=s.candidate_count,
        method=METHOD,
        latency_ms=elapsed_ms(started),
    )


def kafka_handler(engine):
    return lambda message_id, text: handle(engine, message_id, text).model_dump(mode="json")


def build_app(engine_loader: Callable = load_engine) -> FastAPI:
    app = create_app(
        service="summarization",
        title="Summarization Service",
        description=(
            f"Extractive summaries ({METHOD}) using `{MODEL_ID}` embeddings. Returns the 1-2 most "
            "representative sentences verbatim with offsets. Never generates new text."
        ),
        version=VERSION,
        load_engine=engine_loader,
        kafka_service="summarization",
        kafka_handler=kafka_handler,
    )

    @app.post("/process", response_model=SummaryResult, tags=["summarization"], summary="Summarize one redacted message")
    def process(req: ProcessRequest, request: Request) -> SummaryResult:
        return handle(request.app.state.engine, req.message_id, req.text)

    return app


app = build_app()
