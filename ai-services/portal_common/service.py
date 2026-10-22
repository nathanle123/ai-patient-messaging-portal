"""App factory shared by every AI service.

Guarantees, so individual services cannot forget them:
- the model loads once, in the lifespan hook, before the first request is accepted;
- validation errors never echo the request body (FastAPI's default 422 includes ``input``,
  which for the PHI service would be raw, unredacted text);
- unexpected errors are logged by type only and answered with a generic 500;
- request logs carry method, path, status and latency, never payloads.
"""

from __future__ import annotations

import logging
import os
import time
from collections.abc import Callable
from contextlib import asynccontextmanager
from typing import Any, Protocol

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .schemas import HealthResponse, ModelInfo

log = logging.getLogger("portal")


class Engine(Protocol):
    def model_info(self) -> ModelInfo: ...


def configure_logging() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def create_app(
    *,
    service: str,
    title: str,
    description: str,
    version: str,
    load_engine: Callable[[], Any],
    health_checks: Callable[[Any], dict[str, str]] | None = None,
    kafka_service: str | None = None,
    kafka_handler: Callable[[Any], Callable] | None = None,
) -> FastAPI:
    """Build a service app. ``load_engine`` runs at startup; the result is ``app.state.engine``.

    ``health_checks`` may return extra named checks; any value other than ``"ok"`` makes
    ``/health`` answer 503. If ``kafka_handler`` is given and ``KAFKA_BOOTSTRAP`` is set, a
    :class:`~portal_common.worker.KafkaWorker` consumes ``messages.redacted`` in consumer group
    ``kafka_service`` and publishes to ``results.<kafka_service>``.
    """

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        started = time.perf_counter()
        app.state.engine = load_engine()
        app.state.worker = None
        bootstrap = os.environ.get("KAFKA_BOOTSTRAP")
        if kafka_handler is not None and bootstrap:
            from .worker import start_worker

            info = app.state.engine.model_info()
            model_version = f"{info.name}@{info.revision}" if info.revision else info.name
            app.state.worker = start_worker(
                kafka_service, kafka_handler(app.state.engine), bootstrap, model_version=model_version[:128]
            )
        log.info("%s ready in %.1fs", service, time.perf_counter() - started)
        yield
        if app.state.worker is not None:
            app.state.worker.stop()
            app.state.worker.join(timeout=10)
        close = getattr(app.state.engine, "close", None)
        if callable(close):
            close()

    app = FastAPI(
        title=title,
        description=description + "\n\nEducational demonstration with synthetic data. Not clinically validated.",
        version=version,
        lifespan=lifespan,
    )

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        # Field location, message and error type only: no "input", no "ctx".
        errors = [{"loc": list(e.get("loc", ())), "msg": e.get("msg"), "type": e.get("type")} for e in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": errors})

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled %s on %s %s", type(exc).__name__, request.method, request.url.path)
        return JSONResponse(status_code=500, content={"detail": "internal error"})

    @app.middleware("http")
    async def access_log(request: Request, call_next):
        started = time.perf_counter()
        response = await call_next(request)
        if request.url.path != "/health":
            log.info(
                "%s %s %d %.0fms",
                request.method,
                request.url.path,
                response.status_code,
                (time.perf_counter() - started) * 1000,
            )
        return response

    @app.get("/health", response_model=HealthResponse, tags=["ops"], summary="Liveness and model readiness")
    def health(request: Request) -> JSONResponse:
        engine = request.app.state.engine
        checks = health_checks(engine) if health_checks else {}
        worker = getattr(request.app.state, "worker", None)
        if worker is not None:
            checks["kafka_worker"] = "ok" if worker.healthy() else "stopped"
        healthy = all(v == "ok" for v in checks.values())
        body = HealthResponse(
            status="ok" if healthy else "degraded",
            service=service,
            version=version,
            model=engine.model_info(),
            checks=checks,
        )
        return JSONResponse(status_code=200 if healthy else 503, content=body.model_dump())

    return app


def elapsed_ms(started: float) -> int:
    return round((time.perf_counter() - started) * 1000)
