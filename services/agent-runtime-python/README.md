# Yuan Agent Runtime v0.2

This package compiles one restricted Workflow IR v0.1 profile into an actual LangGraph `StateGraph`. It accepts the six-node linear chain `chat_input → auth_context → rag_retrieve → answer_with_citation → answer_gate → chat_output`, including the repository's `packages/workflow-contracts/examples/hr-policy-qa.json`. Node IDs and resource IDs may vary; the edge order, typed ports, five payload schemas, and node configurations must pass the independent Python preflight. Branches, loops, tools, human approvals, subgraphs, and the advanced example are rejected before execution. The wider v0.1 registry's `declared` status and JavaScript validator's `valid` result do not authorize Python execution.

## Install and verify

From this directory:

```sh
uv sync --locked
./.venv/bin/python -m unittest discover -s tests -v
```

`pyproject.toml` pins LangGraph 1.2.12 and `langgraph-checkpoint-sqlite` 3.1.1; `uv.lock` fixes the full dependency resolution. Python 3.10 or later is required. The test adapters are synthetic and explicitly test-only. The package has no default retrieval, model, answer gate, or authorization implementation and no credentials or HR policy content.

Each test keeps its SQLite checkpoint in `artifacts/test-runs/<unique-id>/` for inspection. The virtual environment, test databases, and other generated files are retained after the run; ask the user before cleaning them up.

## Host integration

The hosting server loads the IR with `AgentRuntime.from_file(path, checkpoint_path=..., authorization=..., retrieval=..., model=..., answer_gate=...)`. Each adapter follows the protocol in `src/yuan_agent_runtime/runtime.py`. `AuthorizationAdapter.authorize(trusted_request_context)` must derive the tenant, principal, and capabilities from the server's authenticated request context. The host must **not** build that context from the message body. `require_binding` must resolve each IR resource request against a trusted catalog and return the literal `True` only when authorized; binding IDs and versions in the IR are untrusted declarations. Runtime construction fails if any adapter is absent.

```python
result = runtime.start(
    thread_id=server_thread_id,
    user_message=message_text,
    trusted_request_context=authenticated_server_context,
)
# For an intentionally paused run:
paused = runtime.start(
    thread_id=another_thread_id,
    user_message=message_text,
    trusted_request_context=authenticated_server_context,
    pause_after_retrieval=True,
)
resumed = runtime.resume(
    thread_id=another_thread_id,
    trusted_request_context=fresh_authenticated_server_context,
)
```

`start` rejects an existing thread. `resume` only accepts the checkpoint paused after `rag_retrieve`; it does not rerun retrieval. Each call obtains fresh authorization and checks all four resource bindings. The SQLite key is derived from tenant, principal, and public thread ID, and the checkpoint records workflow ID/version, schema version, compiler version, semantic IR hash, and an identity/capability digest. A different workflow, compiler, principal, or capability set is rejected. Canvas `ui_metadata` and non-execution `extensions` are excluded from the semantic hash.

## Execution and state boundary

The graph state uses exactly the IR's declared top-level fields: `user_message`, `runtime_context`, `evidence`, `draft`, and `answer`. `runtime_context` contains counters, a sanitized node trace, checkpoint binding, and the copied authorized request. Authentication credentials and the `AuthContext` object are never checkpointed. The SQLite checkpoint **does** persist user message, retrieved evidence, draft, and answer content; access to the local DB and its parent directory must be protected by operating-system permissions. The trace returned in `RunResult` records node IDs/types, status, elapsed milliseconds, token count, and micro-unit cost. It contains no message or evidence text.

The runtime checks payload shapes at node boundaries and requires output citations to refer to unique evidence IDs. It stops without releasing an answer when `max_steps`, `max_model_tokens`, `max_duration_ms`, or `max_cost` is exceeded. Adapter exceptions and malformed payloads fail closed with a generic `node_failure`; reported valid usage is still recorded. Time, token, and cost limits are **post-call accounting bounds**, not hard preemption of a blocking synchronous adapter. Active time counts node callbacks, rounded up to milliseconds; it excludes paused time and graph/checkpoint overhead. Adapters receive remaining time/token hints and must implement their own request timeout and output cap. LangGraph's recursion limit is a separate graph safeguard.

This is a local, synchronous execution slice. SQLite is intended for local development and single-process tests here; multi-process coordination, cryptographic checkpoint integrity, production IAM/resource catalogs, real model/retrieval/gate integrations, distributed cancellation, and other workflow nodes remain outside v0.2. The synthetic test evidence must never be used as a production adapter.
