import time
from collections.abc import Callable
from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from portal_common.schemas import ProcessRequest
from portal_common.service import configure_logging, create_app, elapsed_ms

from .engine import CATEGORY_DESCRIPTIONS, MODEL_ID, OTHER_THRESHOLD, load_engine

configure_logging()

VERSION = "0.2.0"

Category = Literal["medication_refill", "scheduling", "test_results", "billing", "symptoms", "other"]


class RoutingResult(BaseModel):
    message_id: UUID
    category: Category
    confidence: float
    scores: dict[str, float] = Field(description="Independent entailment probability per category.")
    latency_ms: int


def handle(engine, message_id: UUID, text: str) -> RoutingResult:
    started = time.perf_counter()
    routing = engine.route(text)
    return RoutingResult(
        message_id=message_id,
        category=routing.category,
        confidence=routing.confidence,
        scores=routing.scores,
        latency_ms=elapsed_ms(started),
    )


def kafka_handler(engine):
    return lambda message_id, text: handle(engine, message_id, text).model_dump(mode="json")


def build_app(engine_loader: Callable = load_engine) -> FastAPI:
    categories = ", ".join(f"`{k}` ({v})" for k, v in CATEGORY_DESCRIPTIONS.items())
    app = create_app(
        service="routing",
        title="Message Routing Service",
        description=(
            f"Zero-shot routing with `{MODEL_ID}` (CPU). Categories: {categories}. A message whose best "
            f"score is below {OTHER_THRESHOLD} is routed to `other`."
        ),
        version=VERSION,
        load_engine=engine_loader,
        kafka_service="routing",
        kafka_handler=kafka_handler,
    )

    @app.post("/process", response_model=RoutingResult, tags=["routing"], summary="Route one redacted message")
    def process(req: ProcessRequest, request: Request) -> RoutingResult:
        return handle(request.app.state.engine, req.message_id, req.text)

    return app


app = build_app()
