# Yuan Agent API v0.3: internal protocol boundary

This package implements a versioned, HMAC-authenticated Java → Python HTTP boundary. It does **not** provide a production `ACTIVE` Release Catalog, a v0.2 Runtime bridge, retrieval, model, authorization, or answer gate adapters. `create_app` has no default keys, catalog, or executor. A signed request cannot produce an Agent answer unless the host explicitly injects a trusted catalog and a real execution backend. The stubs under `tests/` exist only in tests. Passing these protocol tests does not establish a real Agent business flow.

## Install and verify

From this directory:

```sh
uv sync --locked --extra test
./.venv/bin/python -m unittest discover -s tests -v
```

`pyproject.toml` and `uv.lock` capture the package and dependency resolution. The test extra includes locked Uvicorn for the opt-in Java→Python live HTTP check (`bash services/platform-java/tests/run-live-agent-protocol.sh` from the repository root). Its Catalog and executor live only in `tests/live_protocol_server.py`; the production app factory has no defaults. Each Python unit test retains its SQLite database under `artifacts/test-runs/<uuid>/`; the live test retains SQLite and logs under `services/platform-java/target/agent-live-protocol/`. `.venv` and all generated artifacts remain in place until the user approves any cleanup. The test key and fixed signature vector in `tests/vectors/hmac-v1.json` are public, test-only data.

## Endpoints and bodies

All routes are internal. Deploy behind TLS on a private network or authenticated service mesh; do not expose them to a browser. No request body contains `tenant_id`, `principal_id`, capabilities, or resource grants. Java must derive those values from its authenticated user, checked tenant membership, authorization rules, and trusted catalog before signing the assertion.

| Method/path | Body | Result |
| --- | --- | --- |
| `POST /internal/v1/runs` | `contract_version`, `request_id`, `run_id`, `thread_id`, `workflow_ref {id,version,semantic_sha256}`, `input {text}` | Start a new run; Java generates UUIDs. |
| `GET /internal/v1/runs/{run_id}` | Empty | Read the recorded state after an ambiguous HTTP timeout. |
| `POST /internal/v1/runs/{run_id}/resume` | `contract_version`, new `request_id` | Resume an existing `PAUSED` run. |

The response has `contract_version`, `run_id`, `workflow_ref`, `status` (`IN_PROGRESS`, `COMPLETED`, `PAUSED`, `STOPPED`, `FAILED`), `answer` or `null`, `usage` or `null`, `trace_id`, and `stop_reason` or `null`. `usage=null` means it was not measured, never zero by assumption. `trace_id` currently equals the run UUID for correlation; this package does not create or expose a full node Trace. Errors have `{contract_version,error:{code,message}}` without adapter exception text.

## HMAC assertion v1

Required request headers:

```text
X-Yuan-Key-Id: <registered key ID>
X-Yuan-Assertion: base64url(UTF-8 assertion JSON), without padding
X-Yuan-Signature: base64url(HMAC-SHA256(secret[key ID], ASCII("YUAN-HMAC-V1\n" + X-Yuan-Assertion))), without padding
```

The signed assertion contains `assertion_version="1.0.0"`, `iss="yuan-platform-java"`, `aud="yuan-agent-api-python"`, `iat`/`exp` in Unix seconds, `deadline_epoch_ms`, UUID `jti`, uppercase HTTP `method`, exact ASCII `path`, lowercase hex `body_sha256=SHA256(raw HTTP body bytes)`, UUID `tenant_id`/`principal_id`, capabilities, workflow reference, UUID `run_id`/`thread_id`/`request_id`, and exactly one grant for each of `models`, `prompts`, `knowledge_bases`, and `indexes`. Each grant is `{group,resource_id,version}`. The grants must exactly match the trusted Release Catalog's required grants. The assertion's workflow reference must match the body and the catalog's immutable `ACTIVE` release. No IR or filesystem path is accepted from the caller.

The HMAC covers the *encoded original assertion bytes*, so JSON field order is immaterial if Java sends and signs the same bytes. Python verifies the HMAC before parsing assertion JSON, checks observed method/path/raw body hash, issuer/audience, a maximum 60-second token lifetime, a bounded issue time, `exp`, and a durable one-time `jti`. It then validates the body using strict Pydantic models that forbid extra fields. The fixed vector at [`tests/vectors/hmac-v1.json`](tests/vectors/hmac-v1.json) gives Java and Python a byte-exact cross-language check; its historical timestamps are intentionally unsuitable for a live request. Rotate keys with new key IDs and keep secret material outside source control.

`deadline_epoch_ms` is signed and must not exceed `exp`. The API rejects an expired deadline **before** dispatch. It does not forcibly terminate a blocking synchronous backend call. Java should set connection and response timeouts, then query `GET` with a fresh assertion after an ambiguous timeout; it must not blindly reissue `start` or `resume` as a new operation.

## Replay, persistence, and errors

The SQLite ledger stores one-time nonces, run state, and per-operation idempotency records. Each `jti` is globally unique across key IDs, so changing the key ID during rotation cannot replay the same signed assertion even if two IDs temporarily share a secret. A retry uses a fresh `jti` and the **same** `request_id` and raw body. An identical request returns its recorded response without calling the executor again; a changed body or different operation with that request ID returns `409`. One `(tenant,principal,thread)` can own only one run, and an in-progress run blocks a second operation. Access to a run under another tenant, principal, thread, or workflow reference returns `404`. A fresh assertion and catalog/grant check is required even when returning an idempotent replay or status.

| HTTP status | Typical code / meaning |
| --- | --- |
| `400` | `INVALID_REQUEST`: malformed strict body or inconsistent fields. |
| `401` | `INVALID_SERVICE_ASSERTION`: missing/bad signature, stale assertion, reused `jti`, or path/body mismatch. |
| `403` | `RESOURCE_DENIED`: capability or exact grant mismatch. |
| `404` | `RUN_NOT_FOUND`: unknown or inaccessible run/release. |
| `409` | `RUN_CONFLICT`: idempotency conflict, duplicate thread/run, or invalid resume state. |
| `422` | `WORKFLOW_UNSUPPORTED`: release not `ACTIVE` or reference mismatch. |
| `503` | `RELEASE_CATALOG_UNAVAILABLE`, `ADAPTER_UNAVAILABLE`, or `SERVICE_UNAVAILABLE`. |
| `504` | `DEADLINE_EXCEEDED` before backend dispatch. |

The ledger directory must be private (`0700` or stricter) and the DB must be `0600` or stricter. This protects SQLite WAL/SHM files as well as the main database, which can contain answers. Nonce and test artifacts are retained; there is no automatic cleanup. The current SQLite profile is for one local service instance. Deploying multiple workers requires shared concurrency, nonce, and idempotency coordination, plus an appropriate LangGraph checkpoint store. If the catalog is unavailable, even `GET` status returns `503`; an ambiguous timeout then remains unresolved until the catalog returns.

## Runtime integration boundary

The host must implement `TrustedReleaseCatalog` and `ExecutionBackend` from `src/yuan_agent_api/app.py`. The catalog must resolve the signed ID/version/hash to an immutable, authorized `ACTIVE` artifact and verify each resource's actual version and index manifest. The backend must construct the v0.2 `AgentRuntime` from that trusted artifact, provide real authorization/retrieval/model/answer-gate adapters, and pass only the verified internal context to `start`/`resume`. The current v0.2 preflight accepts only `DRAFT` and has no public status method or cross-process thread lock; those boundaries must be resolved and tested before declaring a production Agent invocation. No test adapter may be registered in the production app factory.
