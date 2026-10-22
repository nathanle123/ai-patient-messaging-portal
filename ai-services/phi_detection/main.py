import time
from collections import Counter
from collections.abc import Callable
from uuid import UUID

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from portal_common.schemas import ProcessRequest
from portal_common.service import configure_logging, create_app, elapsed_ms

from .engine import ENTITIES, load_engine

configure_logging()

VERSION = "0.2.0"


class DetectedEntity(BaseModel):
    entity_type: str
    start: int = Field(description="Offset in the original text.")
    end: int
    score: float


class PhiResult(BaseModel):
    message_id: UUID
    redacted_text: str
    entities: list[DetectedEntity] = Field(description="Types and offsets only; the original values are never returned.")
    entity_counts: dict[str, int]
    phi_entity_count: int
    latency_ms: int


def build_app(engine_loader: Callable = load_engine) -> FastAPI:
    app = create_app(
        service="phi-detection",
        title="PHI Detection Service",
        description=(
            "Detects and redacts PHI with Microsoft Presidio, spaCy `en_core_web_lg` NER (names, places) "
            "and custom regex recognizers (phone, email, MRN, date of birth, street address). "
            f"Entities: {', '.join(ENTITIES)}. Detected spans are replaced with `<ENTITY_TYPE>`."
        ),
        version=VERSION,
        load_engine=engine_loader,
    )

    @app.post("/process", response_model=PhiResult, tags=["phi"], summary="Redact PHI from one message")
    def process(req: ProcessRequest, request: Request) -> PhiResult:
        started = time.perf_counter()
        redaction = request.app.state.engine.redact(req.text)
        counts = Counter(s.entity_type for s in redaction.spans)
        return PhiResult(
            message_id=req.message_id,
            redacted_text=redaction.redacted_text,
            entities=[DetectedEntity(**vars(s)) for s in redaction.spans],
            entity_counts=dict(counts),
            phi_entity_count=sum(counts.values()),
            latency_ms=elapsed_ms(started),
        )

    return app


app = build_app()
