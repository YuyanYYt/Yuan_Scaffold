"""Versioned wire models. No browser-provided identity fields exist in bodies."""

from __future__ import annotations

from enum import Enum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


PROTOCOL_VERSION = "1.0.0"
STRICT = ConfigDict(extra="forbid", strict=True, frozen=True)


class WorkflowRef(BaseModel):
    model_config = STRICT
    id: str = Field(pattern=r"^[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*$", max_length=128)
    version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
    semantic_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class Grant(BaseModel):
    model_config = STRICT
    group: Literal["models", "prompts", "knowledge_bases", "indexes"]
    resource_id: str = Field(pattern=r"^[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*$", max_length=128)
    version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")


class InputText(BaseModel):
    model_config = STRICT
    text: str = Field(min_length=1, max_length=16000)


class StartBody(BaseModel):
    model_config = STRICT
    contract_version: Literal["1.0.0"]
    request_id: UUID
    run_id: UUID
    thread_id: UUID
    workflow_ref: WorkflowRef
    input: InputText


class ResumeBody(BaseModel):
    model_config = STRICT
    contract_version: Literal["1.0.0"]
    request_id: UUID


class InvocationAssertion(BaseModel):
    model_config = STRICT
    assertion_version: Literal["1.0.0"]
    iss: str = Field(min_length=1, max_length=128)
    aud: str = Field(min_length=1, max_length=128)
    iat: int
    exp: int
    deadline_epoch_ms: int = Field(gt=0)
    jti: UUID
    method: Literal["GET", "POST"]
    path: str = Field(min_length=1, max_length=256)
    body_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    tenant_id: UUID
    principal_id: UUID
    capabilities: tuple[str, ...]
    workflow_ref: WorkflowRef
    run_id: UUID
    thread_id: UUID
    request_id: UUID
    grants: tuple[Grant, ...]


class RunStatus(str, Enum):
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    PAUSED = "PAUSED"
    STOPPED = "STOPPED"
    FAILED = "FAILED"


class Usage(BaseModel):
    model_config = STRICT
    steps: int = Field(ge=0)
    total_tokens: int = Field(ge=0)
    cost_micro: int = Field(ge=0)
    active_ms: int = Field(ge=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")


class AnswerEnvelope(BaseModel):
    model_config = STRICT
    status: Literal["answered", "abstained", "clarify", "escalated"]
    text: str
    citation_ids: list[str]


class RunResponse(BaseModel):
    model_config = STRICT
    contract_version: Literal["1.0.0"]
    run_id: UUID
    workflow_ref: WorkflowRef
    status: RunStatus
    answer: AnswerEnvelope | None
    usage: Usage | None
    trace_id: UUID
    stop_reason: str | None


class ErrorResponse(BaseModel):
    model_config = STRICT
    contract_version: Literal["1.0.0"] = PROTOCOL_VERSION
    error: dict[str, str]
