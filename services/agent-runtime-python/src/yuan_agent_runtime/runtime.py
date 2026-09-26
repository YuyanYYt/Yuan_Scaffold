"""Synchronous six-node LangGraph executor; all external effects are injected."""

from __future__ import annotations

import copy
import hashlib
import json
import math
import sqlite3
import time
from contextlib import contextmanager, closing
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol, TypedDict

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.graph import END, START, StateGraph

from .preflight import COMPILER_VERSION, ExecutableIR, PreflightError, preflight, validate_payload


class RuntimeExecutionError(RuntimeError):
    pass


@dataclass(frozen=True)
class AuthContext:
    tenant_id: str
    principal_id: str
    capabilities: tuple[str, ...]


@dataclass(frozen=True)
class AdapterResult:
    payload: dict
    tokens_used: int = 0
    cost_micro: int = 0
    currency: str = "USD"


class AuthorizationAdapter(Protocol):
    def authorize(self, trusted_request_context: object) -> AuthContext: ...
    def require_binding(self, auth: AuthContext, group: str, binding: dict) -> bool: ...


class RetrievalAdapter(Protocol):
    def retrieve(self, *, query: str, knowledge_base: dict, index: dict, config: dict,
                 auth: AuthContext, remaining_ms: int) -> AdapterResult: ...


class ModelAdapter(Protocol):
    def generate(self, *, message: dict, evidence: dict, model: dict, prompt: dict,
                 auth: AuthContext, max_tokens: int, remaining_ms: int) -> AdapterResult: ...


class AnswerGateAdapter(Protocol):
    def evaluate(self, *, draft: dict, evidence: dict, checks: tuple[str, ...],
                 auth: AuthContext, remaining_ms: int) -> AdapterResult: ...


class GraphState(TypedDict, total=False):
    user_message: dict
    runtime_context: dict
    evidence: dict
    draft: dict
    answer: dict


@dataclass(frozen=True)
class RunResult:
    status: str
    answer: dict | None
    stop_reason: str | None
    steps: int
    total_tokens: int
    cost_micro: int
    active_ms: int
    trace: tuple[dict, ...]


def _require_auth_context(auth: Any) -> AuthContext:
    if not isinstance(auth, AuthContext):
        raise RuntimeExecutionError("authorization adapter did not return AuthContext")
    if any(not isinstance(s, str) or not s for s in (auth.tenant_id, auth.principal_id)):
        raise RuntimeExecutionError("invalid trusted identity")
    if not isinstance(auth.capabilities, tuple) or any(not isinstance(s, str) or not s for s in auth.capabilities):
        raise RuntimeExecutionError("invalid trusted capabilities")
    if len(set(auth.capabilities)) != len(auth.capabilities):
        raise RuntimeExecutionError("duplicate trusted capabilities")
    return auth


def _auth_digest(auth: AuthContext) -> str:
    data = (auth.tenant_id, auth.principal_id, sorted(auth.capabilities))
    return hashlib.sha256(json.dumps(data, separators=(",", ":")).encode()).hexdigest()


def _thread_key(public_thread_id: str, auth: AuthContext) -> str:
    if not isinstance(public_thread_id, str) or not public_thread_id or len(public_thread_id) > 128:
        raise RuntimeExecutionError("invalid thread ID")
    data = (auth.tenant_id, auth.principal_id, public_thread_id)
    return hashlib.sha256(json.dumps(data, separators=(",", ":")).encode()).hexdigest()


class AgentRuntime:
    """One compiled workflow and one SQLite checkpointer path.

    The embedding server supplies trusted_request_context. Its authorization
    adapter must independently resolve identity and each requested resource.
    No adapters have production defaults.
    """

    def __init__(self, ir: dict, *, checkpoint_path: str | Path,
                 authorization: AuthorizationAdapter | None = None,
                 retrieval: RetrievalAdapter | None = None,
                 model: ModelAdapter | None = None,
                 answer_gate: AnswerGateAdapter | None = None):
        self.ir: ExecutableIR = preflight(ir)
        # Keep a canonical snapshot. Public dataclass members contain ordinary
        # dictionaries; rechecking them at each entry prevents stale hashes.
        self._original_semantic_hash = self.ir.semantic_hash
        if any(adapter is None for adapter in (authorization, retrieval, model, answer_gate)):
            raise RuntimeExecutionError("all production adapters are required")
        self.authorization = authorization
        self.retrieval = retrieval
        self.model = model
        self.answer_gate = answer_gate
        self.checkpoint_path = Path(checkpoint_path)
        if not self.checkpoint_path.parent.is_dir():
            raise RuntimeExecutionError("checkpoint parent directory does not exist")

    @classmethod
    def from_file(cls, path: str | Path, **kwargs: Any) -> AgentRuntime:
        with Path(path).open("r", encoding="utf-8") as source:
            document = json.load(source)
        return cls(document, **kwargs)

    def _fresh_auth(self, trusted_request_context: object) -> AuthContext:
        try:
            auth = _require_auth_context(self.authorization.authorize(trusted_request_context))
            config = {node["type"]: node["config"] for node in self.ir.nodes}
            for group, binding_id in (
                ("knowledge_bases", config["rag_retrieve"]["knowledge_base_binding_id"]),
                ("indexes", config["rag_retrieve"]["index_binding_id"]),
                ("models", config["answer_with_citation"]["model_binding_id"]),
                ("prompts", config["answer_with_citation"]["prompt_binding_id"]),
            ):
                allowed = self.authorization.require_binding(auth, group, copy.deepcopy(self._binding(group, binding_id)))
                if allowed is not True:
                    raise PermissionError("binding not explicitly authorized")
            return auth
        except Exception as exc:
            raise RuntimeExecutionError("authorization denied or unavailable") from exc

    def _guard_ir(self) -> None:
        try:
            fresh = preflight(self.ir.document)
        except PreflightError as exc:
            raise RuntimeExecutionError("workflow changed after compilation") from exc
        if fresh.semantic_hash != self._original_semantic_hash:
            raise RuntimeExecutionError("workflow changed after compilation")

    def _binding(self, group: str, binding_id: str) -> dict:
        return next(entry for entry in self.ir.bindings[group] if entry["id"] == binding_id)

    @contextmanager
    def _saver(self):
        # Explicit allowlist mode avoids process-wide environment changes.
        # Checkpoint DB still needs OS permissions: integrity is not provided
        # by SQLite or this serializer.
        with closing(sqlite3.connect(str(self.checkpoint_path), check_same_thread=False)) as connection:
            yield SqliteSaver(connection, serde=JsonPlusSerializer(allowed_msgpack_modules=None))

    def _checkpoint_binding(self, auth: AuthContext) -> dict:
        return {
            "workflow_id": self.ir.document["workflow_id"],
            "workflow_version": self.ir.document["version"],
            "schema_version": self.ir.document["schema_version"],
            "compiler_version": COMPILER_VERSION,
            "semantic_hash": self.ir.semantic_hash,
            "auth_digest": _auth_digest(auth),
        }

    def _graph(self, saver: SqliteSaver, auth: AuthContext):
        builder = StateGraph(GraphState)
        node_names = [node["id"] for node in self.ir.nodes]
        for node in self.ir.nodes:
            builder.add_node(node["id"], self._execute_node(node, auth))
        builder.add_edge(START, node_names[0])
        for current, successor in zip(node_names, node_names[1:]):
            builder.add_conditional_edges(
                current,
                lambda state: "next" if state["runtime_context"].get("status") == "running" else "stop",
                {"next": successor, "stop": END},
            )
        builder.add_edge(node_names[-1], END)
        return builder.compile(checkpointer=saver)

    def _execute_node(self, node: dict, auth: AuthContext):
        kind, config = node["type"], node["config"]
        policy = self.ir.policy

        def execute(state: GraphState) -> dict:
            meta = state["runtime_context"]
            if meta.get("checkpoint_binding") != self._checkpoint_binding(auth):
                raise RuntimeExecutionError("checkpoint binding mismatch")
            steps = meta.get("steps", 0)
            spent_ms = meta.get("active_ms", 0)
            tokens = meta.get("total_tokens", 0)
            cost = meta.get("cost_micro", 0)
            reason = None
            if steps >= policy["max_steps"]:
                reason = "max_steps"
            elif spent_ms >= policy["max_duration_ms"]:
                reason = "max_duration_ms"
            elif tokens >= policy["max_model_tokens"]:
                reason = "max_model_tokens"
            elif policy["max_cost"]["amount_micro"] > 0 and cost >= policy["max_cost"]["amount_micro"]:
                reason = "max_cost"
            if reason:
                return {"runtime_context": {**meta, "status": "stopped", "stop_reason": reason}}
            started = time.perf_counter()
            result = AdapterResult(payload={})
            output: dict = {}
            status = "running"
            try:
                remaining_ms = max(1, policy["max_duration_ms"] - spent_ms)
                if kind == "chat_input":
                    validate_payload("UserMessage", state["user_message"])
                    output["user_message"] = state["user_message"]
                elif kind == "auth_context":
                    validate_payload("UserMessage", state["user_message"])
                    meta = {**meta, "authorized_request": dict(state["user_message"])}
                    validate_payload("AuthorizedRequest", meta["authorized_request"])
                elif kind == "rag_retrieve":
                    validate_payload("AuthorizedRequest", meta["authorized_request"])
                    result = self.retrieval.retrieve(
                        query=meta["authorized_request"]["text"],
                        knowledge_base=copy.deepcopy(self._binding("knowledge_bases", config["knowledge_base_binding_id"])),
                        index=copy.deepcopy(self._binding("indexes", config["index_binding_id"])),
                        config=copy.deepcopy(config["retrieval"]), auth=auth, remaining_ms=remaining_ms,
                    )
                    self._validate_adapter_result(result, policy)
                    validate_payload("EvidenceSet", result.payload)
                    output["evidence"] = copy.deepcopy(result.payload)
                elif kind == "answer_with_citation":
                    validate_payload("EvidenceSet", state["evidence"])
                    remaining_tokens = min(config["max_output_tokens"], policy["max_model_tokens"] - tokens)
                    if remaining_tokens <= 0:
                        return {"runtime_context": {**meta, "status": "stopped", "stop_reason": "max_model_tokens"}}
                    result = self.model.generate(
                        message=copy.deepcopy(state["user_message"]), evidence=copy.deepcopy(state["evidence"]),
                        model=copy.deepcopy(self._binding("models", config["model_binding_id"])),
                        prompt=copy.deepcopy(self._binding("prompts", config["prompt_binding_id"])),
                        auth=auth, max_tokens=remaining_tokens, remaining_ms=remaining_ms,
                    )
                    self._validate_adapter_result(result, policy)
                    validate_payload("AnswerDraft", result.payload)
                    self._check_citations(result.payload["citation_ids"], state["evidence"])
                    output["draft"] = copy.deepcopy(result.payload)
                elif kind == "answer_gate":
                    validate_payload("AnswerDraft", state["draft"])
                    validate_payload("EvidenceSet", state["evidence"])
                    result = self.answer_gate.evaluate(
                        draft=copy.deepcopy(state["draft"]), evidence=copy.deepcopy(state["evidence"]),
                        checks=tuple(config["checks"]), auth=auth, remaining_ms=remaining_ms,
                    )
                    self._validate_adapter_result(result, policy)
                    validate_payload("AnswerEnvelope", result.payload)
                    self._check_citations(result.payload["citation_ids"], state["evidence"])
                    if result.payload["status"] == "answered":
                        if not result.payload["citation_ids"] or not set(result.payload["citation_ids"]).issubset(set(state["draft"]["citation_ids"])):
                            raise RuntimeExecutionError("answered output lacks grounded citations")
                    output["answer"] = copy.deepcopy(result.payload)
                elif kind == "chat_output":
                    validate_payload("AnswerEnvelope", state["answer"])
                    status = "completed"
                else:
                    raise RuntimeExecutionError("unsupported node")
            except Exception as exc:
                # Do not persist or expose adapter exceptions, which may contain secrets.
                status = "failed"
                output.clear()
                if not (isinstance(result, AdapterResult) and type(result.tokens_used) is int
                        and result.tokens_used >= 0 and type(result.cost_micro) is int
                        and result.cost_micro >= 0 and result.currency == policy["max_cost"]["currency"]):
                    result = AdapterResult(payload={})
                reason = "node_failure"
                _ = exc
            elapsed = max(0, math.ceil((time.perf_counter() - started) * 1000))
            tokens += result.tokens_used
            cost += result.cost_micro
            spent_ms += elapsed
            if status != "failed":
                if tokens > policy["max_model_tokens"]:
                    status, reason = "stopped", "max_model_tokens"
                elif cost > policy["max_cost"]["amount_micro"]:
                    status, reason = "stopped", "max_cost"
                elif spent_ms > policy["max_duration_ms"]:
                    status, reason = "stopped", "max_duration_ms"
            if status in {"stopped", "failed"}:
                output.clear()  # never release a partial or over-budget answer
            trace = list(meta.get("trace", []))
            trace.append({
                "node_id": node["id"], "node_type": kind,
                "status": "completed" if status == "running" else status,
                "duration_ms": elapsed, "tokens_used": result.tokens_used,
                "cost_micro": result.cost_micro,
            })
            meta = {**meta, **{
                "status": status, "steps": steps + 1, "total_tokens": tokens,
                "cost_micro": cost, "active_ms": spent_ms, "trace": trace,
            }}
            if reason:
                meta["stop_reason"] = reason
            output["runtime_context"] = meta
            return output

        return execute

    @staticmethod
    def _validate_adapter_result(result: Any, policy: dict) -> None:
        if not isinstance(result, AdapterResult) or not isinstance(result.payload, dict):
            raise RuntimeExecutionError("adapter returned invalid result")
        if type(result.tokens_used) is not int or result.tokens_used < 0 or type(result.cost_micro) is not int or result.cost_micro < 0:
            raise RuntimeExecutionError("adapter returned invalid usage")
        if result.currency != policy["max_cost"]["currency"]:
            raise RuntimeExecutionError("adapter currency mismatch")

    @staticmethod
    def _check_citations(ids: list[str], evidence: dict) -> None:
        available = [item["citation_id"] for item in evidence["items"]]
        if len(available) != len(set(available)) or len(ids) != len(set(ids)) or not set(ids).issubset(set(available)):
            raise RuntimeExecutionError("citation IDs do not match unique evidence")

    @staticmethod
    def _result(state: dict, paused: bool) -> RunResult:
        meta = state.get("runtime_context", {})
        status = "paused" if paused else meta.get("status", "failed")
        return RunResult(
            status=status,
            answer=state.get("answer") if status == "completed" else None,
            stop_reason=meta.get("stop_reason"), steps=meta.get("steps", 0),
            total_tokens=meta.get("total_tokens", 0), cost_micro=meta.get("cost_micro", 0),
            active_ms=meta.get("active_ms", 0), trace=tuple(meta.get("trace", [])),
        )

    def _validate_paused_snapshot(self, values: dict, pending: tuple[str, ...]) -> None:
        expected_next = (self.ir.nodes[3]["id"],)
        meta = values.get("runtime_context")
        if pending != expected_next or not isinstance(meta, dict):
            raise RuntimeExecutionError("unexpected paused checkpoint shape")
        if meta.get("status") != "running" or meta.get("steps") != 3:
            raise RuntimeExecutionError("unexpected paused checkpoint state")
        if "draft" in values or "answer" in values:
            raise RuntimeExecutionError("paused checkpoint contains future output")
        trace = meta.get("trace")
        if not isinstance(trace, list) or len(trace) != 3 or [item.get("node_type") for item in trace if isinstance(item, dict)] != [node["type"] for node in self.ir.nodes[:3]]:
            raise RuntimeExecutionError("paused checkpoint trace does not match graph")
        for name in ("total_tokens", "cost_micro", "active_ms"):
            if type(meta.get(name)) is not int or meta[name] < 0:
                raise RuntimeExecutionError("invalid paused budget counters")
        try:
            validate_payload("UserMessage", values["user_message"])
            validate_payload("AuthorizedRequest", meta["authorized_request"])
            validate_payload("EvidenceSet", values["evidence"])
            if values["user_message"] != meta["authorized_request"]:
                raise RuntimeExecutionError("authorized request differs from input")
            self._check_citations([], values["evidence"])
        except (KeyError, PreflightError) as exc:
            raise RuntimeExecutionError("invalid paused payload") from exc

    def start(self, *, thread_id: str, user_message: str, trusted_request_context: object,
              pause_after_retrieval: bool = False) -> RunResult:
        self._guard_ir()
        if not isinstance(user_message, str) or not user_message:
            raise RuntimeExecutionError("user_message must be a nonempty string")
        auth = self._fresh_auth(trusted_request_context)
        key = _thread_key(thread_id, auth)
        config = {"configurable": {"thread_id": key}, "recursion_limit": 20}
        with self._saver() as saver:
            graph = self._graph(saver, auth)
            existing = graph.get_state(config)
            if existing.values:
                raise RuntimeExecutionError("thread already exists; use resume for a paused run")
            initial: GraphState = {
                "user_message": {"text": user_message},
                "runtime_context": {
                    "status": "running", "steps": 0, "total_tokens": 0,
                    "cost_micro": 0, "active_ms": 0, "trace": [],
                    "checkpoint_binding": self._checkpoint_binding(auth),
                },
            }
            graph.invoke(
                initial, config=config, durability="sync",
                interrupt_after=[self.ir.nodes[2]["id"]] if pause_after_retrieval else None,
            )
            snapshot = graph.get_state(config)
            return self._result(snapshot.values, bool(snapshot.next))

    def resume(self, *, thread_id: str, trusted_request_context: object) -> RunResult:
        self._guard_ir()
        auth = self._fresh_auth(trusted_request_context)
        key = _thread_key(thread_id, auth)
        config = {"configurable": {"thread_id": key}, "recursion_limit": 20}
        with self._saver() as saver:
            graph = self._graph(saver, auth)
            snapshot = graph.get_state(config)
            if not snapshot.values or not snapshot.next:
                raise RuntimeExecutionError("no paused checkpoint for this trusted identity")
            if snapshot.values.get("runtime_context", {}).get("checkpoint_binding") != self._checkpoint_binding(auth):
                raise RuntimeExecutionError("checkpoint workflow, compiler, or identity mismatch")
            self._validate_paused_snapshot(snapshot.values, tuple(snapshot.next))
            graph.invoke(None, config=config, durability="sync")
            completed = graph.get_state(config)
            return self._result(completed.values, bool(completed.next))
