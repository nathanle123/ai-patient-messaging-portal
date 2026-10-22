from uuid import UUID

from pydantic import BaseModel, Field

MAX_TEXT_CHARS = 10_000


class ProcessRequest(BaseModel):
    message_id: UUID = Field(description="Platform message id; echoed back and used in service events.")
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS, description="Message text.")


class ModelInfo(BaseModel):
    name: str
    revision: str | None = None
    detail: str | None = None


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    model: ModelInfo
    checks: dict[str, str] = Field(default_factory=dict)
