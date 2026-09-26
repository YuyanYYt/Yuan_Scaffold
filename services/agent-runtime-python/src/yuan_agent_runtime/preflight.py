"""Deliberately narrow, independent execution profile for Workflow IR v0.1.

This is an execution gate, not a replacement for the wider Node contract validator.
The runtime accepts only the six-node HR Q&A *shape*, with arbitrary resource IDs
and text. It never treats IR bindings as authority to use a resource.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any

from jsonschema import Draft202012Validator


class PreflightError(ValueError):
    pass


COMPILER_VERSION = "yuan-linear-qa/0.2.0"
ORDER = (
    "chat_input",
    "auth_context",
    "rag_retrieve",
    "answer_with_citation",
    "answer_gate",
    "chat_output",
)
PORTS = (
    ((), (("request", "UserMessage"),)),
    ((("request", "UserMessage"),), (("authorized_request", "AuthorizedRequest"),)),
    ((("authorized_request", "AuthorizedRequest"),), (("evidence", "EvidenceSet"),)),
    ((("evidence", "EvidenceSet"),), (("draft", "AnswerDraft"),)),
    ((("draft", "AnswerDraft"),), (("answer", "AnswerEnvelope"),)),
    ((("answer", "AnswerEnvelope"),), ()),
)
_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*$")
_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")


def _object(value: Any, path: str, required: set[str], allowed: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) - allowed or required - set(value):
        raise PreflightError(f"{path}: invalid or missing fields")
    return value


def _string(value: Any, path: str, identifier: bool = False) -> str:
    if not isinstance(value, str) or not value or (identifier and (len(value) > 128 or not _IDENTIFIER.fullmatch(value))):
        raise PreflightError(f"{path}: expected nonempty string")
    return value


def _integer(value: Any, path: str, minimum: int, maximum: int | None = None) -> int:
    if type(value) is not int or value < minimum or (maximum is not None and value > maximum):
        raise PreflightError(f"{path}: out of range integer")
    return value


def _version(value: Any, path: str) -> str:
    if not isinstance(value, str) or not _VERSION.fullmatch(value):
        raise PreflightError(f"{path}: invalid version")
    return value


def _payload_schemas() -> dict[str, dict]:
    def obj(required: list[str], properties: dict) -> dict:
        return {"type": "object", "additionalProperties": False, "required": required, "properties": properties}

    string = {"type": "string"}
    strings = {"type": "array", "items": string}
    return {
        "UserMessage": obj(["text"], {"text": {"type": "string", "minLength": 1}}),
        "AuthorizedRequest": obj(["text"], {"text": {"type": "string", "minLength": 1}}),
        "EvidenceSet": obj(["items"], {"items": {"type": "array", "items": obj(
            ["citation_id", "source_version", "text"],
            {"citation_id": string, "source_version": string, "text": string},
        )}}),
        "AnswerDraft": obj(["text", "citation_ids"], {"text": string, "citation_ids": strings}),
        "AnswerEnvelope": obj(["status", "text", "citation_ids"], {
            "status": {"enum": ["answered", "abstained", "clarify", "escalated"]},
            "text": string, "citation_ids": strings,
        }),
    }


PAYLOAD_SCHEMAS = _payload_schemas()
PAYLOAD_VALIDATORS = {name: Draft202012Validator(schema) for name, schema in PAYLOAD_SCHEMAS.items()}


def validate_payload(name: str, value: Any) -> None:
    if name not in PAYLOAD_VALIDATORS:
        raise PreflightError(f"unsupported payload type: {name}")
    errors = list(PAYLOAD_VALIDATORS[name].iter_errors(value))
    if errors:
        raise PreflightError(f"{name}: invalid node payload: {errors[0].message}")


@dataclass(frozen=True)
class ExecutableIR:
    document: dict
    nodes: tuple[dict, ...]
    bindings: dict
    policy: dict
    semantic_hash: str


def _validate_bindings(bindings: Any) -> dict:
    groups = {"models", "prompts", "knowledge_bases", "indexes", "tools", "subgraphs", "approval_policies"}
    result = _object(bindings, "bindings", groups, groups)
    ids: set[str] = set()
    for group, entries in result.items():
        if not isinstance(entries, list):
            raise PreflightError(f"bindings.{group}: expected array")
        if group in {"tools", "subgraphs", "approval_policies"} and entries:
            raise PreflightError(f"bindings.{group}: unsupported by this runtime")
        for binding in entries:
            fields = {"id", "resource_id", "version"}
            if group == "indexes":
                fields |= {"kind", "build_params"}
            _object(binding, f"bindings.{group}", fields, fields)
            for field in ("id", "resource_id"):
                _string(binding[field], f"bindings.{group}.{field}", True)
            _version(binding["version"], f"bindings.{group}.version")
            if binding["id"] in ids:
                raise PreflightError("binding IDs must be unique across groups")
            ids.add(binding["id"])
            if group == "indexes":
                if binding["kind"] != "hnsw":
                    raise PreflightError("only hnsw index binding is supported")
                params = _object(binding["build_params"], "index.build_params", {"m", "ef_construction"}, {"m", "ef_construction"})
                _integer(params["m"], "m", 2)
                _integer(params["ef_construction"], "ef_construction", max(2, params["m"]))
    return result


def _lookup(bindings: dict, group: str, binding_id: Any) -> dict:
    _string(binding_id, f"{group} binding ID", True)
    matches = [entry for entry in bindings[group] if entry["id"] == binding_id]
    if len(matches) != 1:
        raise PreflightError(f"unresolved {group} binding ID")
    return matches[0]


def _validate_policy(policy: Any) -> dict:
    fields = {"max_steps", "max_duration_ms", "max_tool_calls", "max_model_tokens", "max_cost"}
    result = _object(policy, "execution_policy", fields, fields)
    _integer(result["max_steps"], "max_steps", 1)
    _integer(result["max_duration_ms"], "max_duration_ms", 1)
    _integer(result["max_model_tokens"], "max_model_tokens", 1)
    if result["max_tool_calls"] != 0:
        raise PreflightError("max_tool_calls must be zero for this runtime")
    cost = _object(result["max_cost"], "max_cost", {"amount_micro", "currency"}, {"amount_micro", "currency"})
    _integer(cost["amount_micro"], "max_cost.amount_micro", 0)
    if not isinstance(cost["currency"], str) or not re.fullmatch(r"[A-Z]{3}", cost["currency"]):
        raise PreflightError("max_cost.currency must be an ISO-like three-letter code")
    return result


def _validate_config(node: dict, bindings: dict) -> None:
    kind, config = node["type"], node["config"]
    if kind == "chat_input":
        _object(config, kind, {"source"}, {"source"})
        if config["source"] != "user_message":
            raise PreflightError("unsupported chat input source")
    elif kind == "auth_context":
        _object(config, kind, {"source"}, {"source"})
        if config["source"] != "server_context":
            raise PreflightError("auth must use trusted server context")
    elif kind == "rag_retrieve":
        fields = {"knowledge_base_binding_id", "index_binding_id", "query_state_path", "retrieval"}
        _object(config, kind, fields, fields)
        _lookup(bindings, "knowledge_bases", config["knowledge_base_binding_id"])
        index = _lookup(bindings, "indexes", config["index_binding_id"])
        if config["query_state_path"] != "$.user_message.text":
            raise PreflightError("unsupported query state path")
        retrieval_fields = {"strategy", "dense_top_k", "sparse_top_k", "fusion_top_k", "rerank_top_k", "context_top_k", "ef_search", "config_version"}
        retrieval = _object(config["retrieval"], "retrieval", retrieval_fields, retrieval_fields)
        if retrieval["config_version"] != "0.1.0" or retrieval["strategy"] != "hybrid":
            raise PreflightError("unsupported retrieval configuration")
        for key in ("dense_top_k", "sparse_top_k", "fusion_top_k", "rerank_top_k", "context_top_k", "ef_search"):
            _integer(retrieval[key], key, 1)
        if not (retrieval["context_top_k"] <= retrieval["rerank_top_k"] <= retrieval["fusion_top_k"] <= max(retrieval["dense_top_k"], retrieval["sparse_top_k"])):
            raise PreflightError("inconsistent retrieval top-k values")
        if retrieval["ef_search"] < index["build_params"]["m"]:
            raise PreflightError("ef_search below bound index m")
    elif kind == "answer_with_citation":
        fields = {"model_binding_id", "prompt_binding_id", "max_output_tokens"}
        _object(config, kind, fields, fields)
        _lookup(bindings, "models", config["model_binding_id"])
        _lookup(bindings, "prompts", config["prompt_binding_id"])
        _integer(config["max_output_tokens"], "max_output_tokens", 1)
    elif kind == "answer_gate":
        _object(config, kind, {"checks", "on_fail"}, {"checks", "on_fail"})
        if config["checks"] != ["groundedness", "citation", "policy"] or config["on_fail"] != "abstain":
            raise PreflightError("unsupported answer gate policy")
    elif kind == "chat_output":
        _object(config, kind, {"format"}, {"format"})
        if config["format"] != "answer_envelope":
            raise PreflightError("unsupported chat output format")


def preflight(document: Any) -> ExecutableIR:
    """Reject arbitrary IR; return an immutable-enough checked copy for one run."""
    fields = {"schema_version", "workflow_id", "version", "status", "entry_node_id", "state_schema", "types", "bindings", "execution_policy", "nodes", "edges"}
    allowed = fields | {"ui_metadata", "extensions"}
    ir = copy.deepcopy(_object(document, "workflow", fields, allowed))
    if ir["schema_version"] != "0.1.0" or ir["status"] != "DRAFT":
        raise PreflightError("only Workflow IR v0.1.0 DRAFT is accepted for local runtime")
    _string(ir["workflow_id"], "workflow_id", True)
    _version(ir["version"], "version")
    if ir["types"] != PAYLOAD_SCHEMAS:
        raise PreflightError("this runtime requires its fixed five payload schemas")
    state_schema = ir["state_schema"]
    expected_state_schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object", "additionalProperties": False,
        "required": ["user_message"],
        "properties": {
            "user_message": PAYLOAD_SCHEMAS["UserMessage"],
            "runtime_context": {"type": "object"},
            "evidence": PAYLOAD_SCHEMAS["EvidenceSet"],
            "draft": PAYLOAD_SCHEMAS["AnswerDraft"],
            "answer": PAYLOAD_SCHEMAS["AnswerEnvelope"],
        },
    }
    if state_schema != expected_state_schema:
        raise PreflightError("unsupported state schema")
    Draft202012Validator.check_schema(state_schema)
    bindings = _validate_bindings(ir["bindings"])
    policy = _validate_policy(ir["execution_policy"])
    raw_nodes, raw_edges = ir["nodes"], ir["edges"]
    if not isinstance(raw_nodes, list) or len(raw_nodes) != 6 or not isinstance(raw_edges, list) or len(raw_edges) != 5:
        raise PreflightError("this runtime requires exactly six nodes and five edges")
    by_id: dict[str, dict] = {}
    for node in raw_nodes:
        required = {"id", "type", "type_version", "input_ports", "output_ports", "config"}
        _object(node, "node", required, required)
        node_id = _string(node["id"], "node.id", True)
        if node_id in by_id:
            raise PreflightError("duplicate node ID")
        by_id[node_id] = node
    entry = _string(ir["entry_node_id"], "entry_node_id", True)
    if entry not in by_id:
        raise PreflightError("entry node missing")
    outgoing: dict[str, dict] = {}
    incoming: set[str] = set()
    edge_ids: set[str] = set()
    for edge in raw_edges:
        _object(edge, "edge", {"id", "from", "to"}, {"id", "from", "to"})
        edge_id = _string(edge["id"], "edge.id", True)
        source = _object(edge["from"], "edge.from", {"node_id", "port"}, {"node_id", "port"})
        target = _object(edge["to"], "edge.to", {"node_id", "port"}, {"node_id", "port"})
        if edge_id in edge_ids or source["node_id"] not in by_id or target["node_id"] not in by_id:
            raise PreflightError("duplicate edge or unknown node")
        edge_ids.add(edge_id)
        if source["node_id"] in outgoing or target["node_id"] in incoming:
            raise PreflightError("nonlinear graph is unsupported")
        outgoing[source["node_id"]] = edge
        incoming.add(target["node_id"])
    nodes = []
    cursor = entry
    while cursor in by_id and cursor not in {node["id"] for node in nodes}:
        nodes.append(by_id[cursor])
        cursor = outgoing[cursor]["to"]["node_id"] if cursor in outgoing else None
    if len(nodes) != 6 or cursor is not None or entry in incoming or tuple(n["type"] for n in nodes) != ORDER:
        raise PreflightError("unsupported node sequence or disconnected graph")
    for index, node in enumerate(nodes):
        if node["type_version"] != "0.1.0":
            raise PreflightError("unsupported node type_version")
        for field, signature in (("input_ports", PORTS[index][0]), ("output_ports", PORTS[index][1])):
            expected = [{"name": name, "schema_ref": f"#/types/{typ}", "required": True} for name, typ in signature]
            if node[field] != expected:
                raise PreflightError(f"{node['type']}: unsupported {field}")
        _validate_config(node, bindings)
        if index < 5:
            edge = outgoing[node["id"]]
            if edge["from"]["port"] != PORTS[index][1][0][0] or edge["to"]["port"] != PORTS[index + 1][0][0][0]:
                raise PreflightError("edge port does not match typed linear signature")
    semantic = {k: v for k, v in ir.items() if k not in {"ui_metadata", "extensions"}}
    digest = hashlib.sha256(json.dumps(semantic, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return ExecutableIR(ir, tuple(nodes), bindings, policy, digest)
