"""Internal FastAPI boundary. No production catalog or executor is bundled."""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Protocol
from uuid import UUID

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .auth import HmacVerifier, VerifiedInvocation
from .errors import ApiError, CONFLICT, FORBIDDEN, INVALID_REQUEST, NOT_FOUND, UNAVAILABLE, UNSUPPORTED
from .ledger import RunRecord, SqliteLedger
from .models import (
    AnswerEnvelope, Grant, PROTOCOL_VERSION, ResumeBody, RunResponse, RunStatus,
    StartBody, Usage, WorkflowRef,
)


@dataclass(frozen=True)
class VerifiedContext:
    tenant_id: str
    principal_id: str
    capabilities: tuple[str, ...]
    grants: tuple[Grant, ...]
    request_id: str
    run_id: str
    thread_id: str
    workflow_ref: WorkflowRef
    deadline_epoch_ms: int


@dataclass(frozen=True)
class ReleaseRecord:
    workflow_ref: WorkflowRef
    status: str
    required_grants: tuple[Grant, ...]


@dataclass(frozen=True)
class BackendResult:
    status: RunStatus
    answer: dict | None
    steps: int
    total_tokens: int
    cost_micro: int
    active_ms: int
    currency: str
    stop_reason: str | None = None


class TrustedReleaseCatalog(Protocol):
    def resolve(self, workflow_ref: WorkflowRef, context: VerifiedContext) -> ReleaseRecord | None: ...


class ExecutionBackend(Protocol):
    def start(self, workflow_ref: WorkflowRef, thread_id: str,
              user_message: str, context: VerifiedContext) -> BackendResult: ...
    def resume(self, workflow_ref: WorkflowRef, thread_id: str,
               context: VerifiedContext) -> BackendResult: ...


def _context(invocation: VerifiedInvocation) -> VerifiedContext:
    assertion = invocation.assertion
    return VerifiedContext(
        tenant_id=str(assertion.tenant_id), principal_id=str(assertion.principal_id),
        capabilities=assertion.capabilities, grants=assertion.grants,
        request_id=str(assertion.request_id), run_id=str(assertion.run_id),
        thread_id=str(assertion.thread_id), workflow_ref=assertion.workflow_ref,
        deadline_epoch_ms=assertion.deadline_epoch_ms,
    )


def _parse_body(model: type, raw: bytes):
    try:
        return model.model_validate_json(raw)
    except (ValidationError, ValueError) as exc:
        raise INVALID_REQUEST from exc


def _run_id(value: str) -> str:
    try:
        parsed = UUID(value)
    except (ValueError, AttributeError) as exc:
        raise INVALID_REQUEST from exc
    if str(parsed) != value:
        raise INVALID_REQUEST
    return value


def _in_progress(context: VerifiedContext) -> RunResponse:
    return RunResponse(
        contract_version=PROTOCOL_VERSION, run_id=UUID(context.run_id),
        workflow_ref=context.workflow_ref, status=RunStatus.IN_PROGRESS,
        answer=None, usage=None,
        trace_id=UUID(context.run_id), stop_reason=None,
    )


def _backend_response(context: VerifiedContext, result: BackendResult) -> RunResponse:
    if not isinstance(result, BackendResult) or result.status not in {
        RunStatus.COMPLETED, RunStatus.PAUSED, RunStatus.STOPPED, RunStatus.FAILED,
    }:
        raise ValueError("invalid backend status")
    if result.status == RunStatus.COMPLETED and result.answer is None:
        raise ValueError("completed backend result has no answer")
    if result.status != RunStatus.COMPLETED and result.answer is not None:
        raise ValueError("non-completed backend result contains answer")
    return RunResponse(
        contract_version=PROTOCOL_VERSION, run_id=UUID(context.run_id),
        workflow_ref=context.workflow_ref, status=result.status,
        answer=AnswerEnvelope.model_validate(result.answer) if result.answer is not None else None,
        usage=Usage(steps=result.steps, total_tokens=result.total_tokens,
                    cost_micro=result.cost_micro, active_ms=result.active_ms,
                    currency=result.currency),
        trace_id=UUID(context.run_id), stop_reason=result.stop_reason,
    )


def _failed(context: VerifiedContext) -> RunResponse:
    return RunResponse(
        contract_version=PROTOCOL_VERSION, run_id=UUID(context.run_id),
        workflow_ref=context.workflow_ref, status=RunStatus.FAILED,
        answer=None, usage=None,
        trace_id=UUID(context.run_id), stop_reason="EXECUTION_FAILED",
    )


def _json(response: RunResponse) -> JSONResponse:
    return JSONResponse(status_code=202 if response.status == RunStatus.IN_PROGRESS else 200,
                        content=response.model_dump(mode="json"))


def create_app(*, keyring: Mapping[str, bytes], ledger_path: str | Path,
               catalog: TrustedReleaseCatalog | None = None,
               executor: ExecutionBackend | None = None,
               issuer: str = "yuan-platform-java",
               audience: str = "yuan-agent-api-python") -> FastAPI:
    """Create a closed API unless host supplies all trusted integrations."""
    ledger = SqliteLedger(ledger_path)
    verifier = HmacVerifier(keys=keyring, nonce_store=ledger, issuer=issuer, audience=audience)
    app = FastAPI(title="Yuan Agent Internal API", version="0.3.0", docs_url=None,
                  redoc_url=None, openapi_url=None)

    @app.exception_handler(ApiError)
    async def protocol_error(_request: Request, error: ApiError):
        return JSONResponse(status_code=error.status_code,
                            content={"contract_version": PROTOCOL_VERSION,
                                     "error": {"code": error.code, "message": error.code}})

    def release_for(context: VerifiedContext, *, require_active: bool) -> ReleaseRecord:
        if catalog is None:
            raise ApiError(503, "RELEASE_CATALOG_UNAVAILABLE")
        try:
            release = catalog.resolve(context.workflow_ref, context)
        except ApiError:
            raise
        except Exception as exc:
            raise ApiError(503, "RELEASE_CATALOG_UNAVAILABLE") from exc
        if release is None:
            raise NOT_FOUND
        if not isinstance(release, ReleaseRecord) or release.workflow_ref != context.workflow_ref:
            raise UNSUPPORTED
        if require_active and release.status != "ACTIVE":
            raise UNSUPPORTED
        requested = {(g.group, g.resource_id, g.version) for g in context.grants}
        required = {(g.group, g.resource_id, g.version) for g in release.required_grants}
        if requested != required or len(requested) != 4 or {g[0] for g in requested} != {
            "models", "prompts", "knowledge_bases", "indexes",
        }:
            raise FORBIDDEN
        return release

    async def invoke_backend(context: VerifiedContext, kind: str,
                             message: str | None = None) -> RunResponse:
        try:
            if kind == "start":
                result = await asyncio.to_thread(executor.start, context.workflow_ref,
                                                 context.thread_id, message, context)
            else:
                result = await asyncio.to_thread(executor.resume, context.workflow_ref,
                                                 context.thread_id, context)
            return _backend_response(context, result)
        except Exception:
            # Adapter exceptions can contain credentials or user content.
            return _failed(context)

    def require_deadline(context: VerifiedContext) -> None:
        if int(time.time() * 1000) >= context.deadline_epoch_ms:
            raise ApiError(504, "DEADLINE_EXCEEDED")

    @app.post("/internal/v1/runs")
    async def start(request: Request):
        invocation = await verifier.verify(request)
        body = _parse_body(StartBody, invocation.raw_body)
        context = _context(invocation)
        if (str(body.request_id) != context.request_id or str(body.run_id) != context.run_id
                or str(body.thread_id) != context.thread_id
                or body.workflow_ref != context.workflow_ref):
            raise INVALID_REQUEST
        if "agent.run" not in context.capabilities:
            raise FORBIDDEN
        release_for(context, require_active=True)
        if executor is None:
            raise ApiError(503, "ADAPTER_UNAVAILABLE")
        require_deadline(context)
        decision = ledger.begin_start(
            run_id=context.run_id, tenant_id=context.tenant_id,
            principal_id=context.principal_id, thread_id=context.thread_id,
            workflow_ref=context.workflow_ref, request_id=context.request_id,
            body_sha256=invocation.assertion.body_sha256,
        )
        if not decision.new:
            return _json(decision.prior_response or _in_progress(context))
        response = await invoke_backend(context, "start", body.input.text)
        ledger.finish(run_id=context.run_id, tenant_id=context.tenant_id,
                      principal_id=context.principal_id, request_id=context.request_id,
                      response=response)
        return _json(response)

    @app.get("/internal/v1/runs/{run_id}")
    async def get_status(request: Request, run_id: str):
        invocation = await verifier.verify(request)
        if invocation.raw_body:
            raise INVALID_REQUEST
        context = _context(invocation)
        if _run_id(run_id) != context.run_id:
            raise NOT_FOUND
        if "agent.run" not in context.capabilities and "agent.read" not in context.capabilities:
            raise FORBIDDEN
        release_for(context, require_active=False)
        record = ledger.get_run(
            run_id=context.run_id, tenant_id=context.tenant_id,
            principal_id=context.principal_id, thread_id=context.thread_id,
            workflow_ref=context.workflow_ref,
        )
        if record.status == RunStatus.IN_PROGRESS:
            return _json(_in_progress(context))
        if record.response is None:
            raise UNAVAILABLE
        return _json(record.response)

    @app.post("/internal/v1/runs/{run_id}/resume")
    async def resume(request: Request, run_id: str):
        invocation = await verifier.verify(request)
        body = _parse_body(ResumeBody, invocation.raw_body)
        context = _context(invocation)
        if _run_id(run_id) != context.run_id or str(body.request_id) != context.request_id:
            raise NOT_FOUND
        if "agent.run" not in context.capabilities:
            raise FORBIDDEN
        release_for(context, require_active=True)
        if executor is None:
            raise ApiError(503, "ADAPTER_UNAVAILABLE")
        require_deadline(context)
        decision = ledger.begin_resume(
            run_id=context.run_id, tenant_id=context.tenant_id,
            principal_id=context.principal_id, thread_id=context.thread_id,
            workflow_ref=context.workflow_ref, request_id=context.request_id,
            body_sha256=invocation.assertion.body_sha256,
        )
        if not decision.new:
            return _json(decision.prior_response or _in_progress(context))
        response = await invoke_backend(context, "resume")
        ledger.finish(run_id=context.run_id, tenant_id=context.tenant_id,
                      principal_id=context.principal_id, request_id=context.request_id,
                      response=response)
        return _json(response)

    return app
