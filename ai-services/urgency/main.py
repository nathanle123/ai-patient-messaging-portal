import time
from collections.abc import Callable
from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from portal_common.schemas import ProcessRequest
from portal_common.service import configure_logging, create_app, elapsed_ms

from .engine import load_engine
from .rules import RULESET_VERSION

configure_logging()

VERSION = "0.2.0"
DISCLAIMER = "Demonstration-only review flag. Not validated clinical triage."


class MatchedFlag(BaseModel):
    phrase: str
    tier: Literal["URGENT", "SOON"]
    negated: bool
    start: int
    end: int
    note: str | None = None


class UrgencyResult(BaseModel):
    message_id: UUID
    label: Literal["urgent", "soon", "routine"]
    flags: list[MatchedFlag] = Field(description="Every red-flag phrase found, including negated ones.")
    ruleset_version: str
    disclaimer: str
    latency_ms: int


def handle(engine, message_id: UUID, text: str) -> UrgencyResult:
    started = time.perf_counter()
    label, flags = engine.classify(text)
    return UrgencyResult(
        message_id=message_id,
        label=label,
        flags=[MatchedFlag(**vars(f)) for f in flags],
        ruleset_version=RULESET_VERSION,
        disclaimer=DISCLAIMER,
        latency_ms=elapsed_ms(started),
    )


def kafka_handler(engine):
    return lambda message_id, text: handle(engine, message_id, text).model_dump(mode="json")


def build_app(engine_loader: Callable = load_engine) -> FastAPI:
    app = create_app(
        service="urgency",
        title="Urgency Flagging Service",
        description=(
            "Flags messages as `urgent`, `soon` or `routine` using red-flag phrase rules (spaCy EntityRuler) "
            "with negation handling (negspaCy), evaluated per sentence. " + DISCLAIMER
        ),
        version=VERSION,
        load_engine=engine_loader,
        kafka_service="urgency",
        kafka_handler=kafka_handler,
    )

    @app.post("/process", response_model=UrgencyResult, tags=["urgency"], summary="Flag one redacted message")
    def process(req: ProcessRequest, request: Request) -> UrgencyResult:
        return handle(request.app.state.engine, req.message_id, req.text)

    return app


app = build_app()
