"""Synthetic protocol tests. Stub catalog/executor never enter production code."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import stat
import threading
import time
import unittest
import uuid
from pathlib import Path

from fastapi.testclient import TestClient

from yuan_agent_api import BackendResult, ReleaseRecord, create_app
from yuan_agent_api.auth import MAX_BODY_BYTES, SIGNING_PREFIX
from yuan_agent_api.models import Grant, RunStatus, WorkflowRef


TEST_KEY = b"test-only-service-hmac-key-32-bytes-minimum"
TEST_KEY_ID = "test-key-1"
TENANT = "12345678-1234-4234-8234-123456789abc"  # numeric prefix is intentional
PRINCIPAL = "22345678-1234-4234-8234-123456789abc"
WORKFLOW = {
    "id": "asset.faq",
    "version": "0.1.0",
    "semantic_sha256": "a" * 64,
}
GRANTS = [
    {"group": "models", "resource_id": "model.asset", "version": "1.0.0"},
    {"group": "prompts", "resource_id": "prompt.asset", "version": "1.0.0"},
    {"group": "knowledge_bases", "resource_id": "kb.asset", "version": "1.0.0"},
    {"group": "indexes", "resource_id": "index.asset", "version": "1.0.0"},
]


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


class TestOnlyCatalog:
    def __init__(self):
        self.status = "ACTIVE"
        self.allowed = True
        self.calls = 0

    def resolve(self, workflow_ref, context):
        self.calls += 1
        if not self.allowed:
            return None
        return ReleaseRecord(
            workflow_ref=WorkflowRef.model_validate(WORKFLOW),
            status=self.status,
            required_grants=tuple(Grant.model_validate(g) for g in GRANTS),
        )


class TestOnlyExecutor:
    def __init__(self):
        self.start_calls = 0
        self.resume_calls = 0
        self.pause = False
        self.entered = threading.Event()
        self.release = threading.Event()
        self.block = False

    def start(self, workflow_ref, thread_id, user_message, context):
        self.start_calls += 1
        assert context.tenant_id == TENANT
        assert context.principal_id == PRINCIPAL
        assert user_message
        if self.block:
            self.entered.set()
            if not self.release.wait(3):
                raise TimeoutError("test-only block timed out")
        if self.pause:
            return BackendResult(RunStatus.PAUSED, None, 3, 4, 0, 3, "USD")
        return BackendResult(RunStatus.COMPLETED,
                             {"status": "answered", "text": "TEST ONLY", "citation_ids": ["c1"]},
                             6, 10, 12, 5, "USD")

    def resume(self, workflow_ref, thread_id, context):
        self.resume_calls += 1
        return BackendResult(RunStatus.COMPLETED,
                             {"status": "answered", "text": "TEST ONLY resumed", "citation_ids": ["c1"]},
                             6, 10, 12, 5, "USD")


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        directory = Path(__file__).resolve().parents[1] / "artifacts/test-runs" / str(uuid.uuid4())
        directory.mkdir(parents=True, mode=0o700)
        self.db = directory / "ledger.sqlite"
        self.catalog = TestOnlyCatalog()
        self.executor = TestOnlyExecutor()
        self.client = TestClient(create_app(keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=self.db,
                                            catalog=self.catalog, executor=self.executor))
        self.run_id = str(uuid.uuid4())
        self.thread_id = str(uuid.uuid4())
        self.request_id = str(uuid.uuid4())

    def start_body(self, **changes):
        body = {
            "contract_version": "1.0.0", "request_id": self.request_id,
            "run_id": self.run_id, "thread_id": self.thread_id,
            "workflow_ref": WORKFLOW,
            "input": {"text": "Where is the laptop policy?"},
        }
        body.update(changes)
        return body

    def signed(self, method, path, body=b"", *, assertion_changes=None, jti=None,
               key=TEST_KEY, key_id=TEST_KEY_ID):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False, sort_keys=True,
                              separators=(",", ":")).encode("utf-8")
        now = int(time.time())
        assertion = {
            "assertion_version": "1.0.0", "iss": "yuan-platform-java",
            "aud": "yuan-agent-api-python", "iat": now, "exp": now + 45,
            "deadline_epoch_ms": (now + 40) * 1000,
            "jti": jti or str(uuid.uuid4()), "method": method, "path": path,
            "body_sha256": hashlib.sha256(body).hexdigest(),
            "tenant_id": TENANT, "principal_id": PRINCIPAL,
            "capabilities": ["agent.run"], "workflow_ref": WORKFLOW,
            "run_id": self.run_id, "thread_id": self.thread_id,
            "request_id": self.request_id, "grants": GRANTS,
        }
        if assertion_changes:
            assertion.update(assertion_changes)
        assertion_b64 = b64url(json.dumps(assertion, ensure_ascii=False, sort_keys=True,
                                          separators=(",", ":")).encode("utf-8"))
        signature = b64url(hmac.new(key, SIGNING_PREFIX + assertion_b64.encode("ascii"),
                                    hashlib.sha256).digest())
        return body, {
            "X-Yuan-Key-Id": key_id,
            "X-Yuan-Assertion": assertion_b64,
            "X-Yuan-Signature": signature,
            "Content-Type": "application/json",
        }

    def send_start(self, *, body=None, assertion_changes=None, jti=None):
        path = "/internal/v1/runs"
        raw, headers = self.signed("POST", path, body or self.start_body(),
                                   assertion_changes=assertion_changes, jti=jti)
        return self.client.post(path, content=raw, headers=headers)

    def send_get(self, *, assertion_changes=None):
        path = f"/internal/v1/runs/{self.run_id}"
        _, headers = self.signed("GET", path, assertion_changes=assertion_changes)
        return self.client.get(path, headers=headers)

    def send_resume(self, *, request_id=None, assertion_changes=None):
        path = f"/internal/v1/runs/{self.run_id}/resume"
        rid = request_id or str(uuid.uuid4())
        body = {"contract_version": "1.0.0", "request_id": rid}
        overrides = {"request_id": rid}
        if assertion_changes:
            overrides.update(assertion_changes)
        raw, headers = self.signed("POST", path, body, assertion_changes=overrides)
        return self.client.post(path, content=raw, headers=headers)

    def test_signed_start_status_and_numeric_uuid_identity(self):
        response = self.send_start()
        self.assertEqual(200, response.status_code)
        self.assertEqual("COMPLETED", response.json()["status"])
        self.assertEqual("TEST ONLY", response.json()["answer"]["text"])
        self.assertEqual(1, self.executor.start_calls)
        self.assertEqual(200, self.send_get().status_code)
        self.assertEqual(0o600, stat.S_IMODE(self.db.stat().st_mode))

    def test_cross_language_hmac_fixed_vector(self):
        vector = json.loads((Path(__file__).parent / "vectors/hmac-v1.json").read_text())
        assertion_raw = vector["assertion_json_utf8"].encode("utf-8")
        assertion_b64 = b64url(assertion_raw)
        self.assertEqual(vector["assertion_b64url"], assertion_b64)
        self.assertEqual(vector["body_sha256"],
                         hashlib.sha256(vector["raw_body_utf8"].encode("utf-8")).hexdigest())
        signature = b64url(hmac.new(vector["secret_utf8"].encode("utf-8"),
                                    SIGNING_PREFIX + assertion_b64.encode("ascii"),
                                    hashlib.sha256).digest())
        self.assertEqual("XL9P19hs8sYN2hmQ7_NidD6USw6EBOuoDp-FwxCBJX0", signature)
        self.assertEqual(vector["signature_b64url"], signature)

    def test_signature_body_path_claim_and_nonce_tampering(self):
        path = "/internal/v1/runs"
        raw, headers = self.signed("POST", path, self.start_body())
        changed = raw.replace(b"laptop", b"tablet")
        self.assertEqual(401, self.client.post(path, content=changed, headers=headers).status_code)
        wrong_sig = dict(headers)
        wrong_sig["X-Yuan-Signature"] = b64url(bytes(32))
        self.assertEqual(401, self.client.post(path, content=raw, headers=wrong_sig).status_code)
        _, wrong_path = self.signed("POST", path, self.start_body(),
                                    assertion_changes={"path": "/internal/v1/other"})
        self.assertEqual(401, self.client.post(path, content=raw, headers=wrong_path).status_code)
        self.assertEqual(200, self.client.post(path, content=raw, headers=headers).status_code)
        self.assertEqual(401, self.client.post(path, content=raw, headers=headers).status_code)

    def test_same_assertion_cannot_replay_across_key_ids_with_shared_secret(self):
        rotated = TestClient(create_app(
            keyring={"old-key": TEST_KEY, "new-key": TEST_KEY}, ledger_path=self.db,
            catalog=self.catalog, executor=self.executor,
        ))
        path = "/internal/v1/runs"
        raw, headers = self.signed("POST", path, self.start_body(), key_id="old-key")
        self.assertEqual(200, rotated.post(path, content=raw, headers=headers).status_code)
        changed_key_id = dict(headers)
        changed_key_id["X-Yuan-Key-Id"] = "new-key"
        response = rotated.post(path, content=raw, headers=changed_key_id)
        self.assertEqual((401, "INVALID_SERVICE_ASSERTION"),
                         (response.status_code, response.json()["error"]["code"]))
        self.assertEqual(1, self.executor.start_calls)

    def test_expired_wrong_audience_and_extra_identity_field(self):
        now = int(time.time())
        self.assertEqual(401, self.send_start(assertion_changes={"iat": now - 100,
                                                                    "exp": now - 10}).status_code)
        self.assertEqual(401, self.send_start(assertion_changes={"aud": "other-service"}).status_code)
        self.assertEqual(400, self.send_start(body=self.start_body(tenant_id=TENANT)).status_code)
        self.assertEqual(504, self.send_start(assertion_changes={"deadline_epoch_ms":
                                  int(time.time() * 1000) - 1}).status_code)
        self.assertEqual(0, self.executor.start_calls)

    def test_idempotent_retry_new_jti_and_conflicting_body(self):
        first = self.send_start()
        second = self.send_start()
        self.assertEqual(first.json(), second.json())
        self.assertEqual(1, self.executor.start_calls)
        changed = self.start_body(input={"text": "Different question"})
        self.assertEqual(409, self.send_start(body=changed).status_code)
        self.assertEqual(1, self.executor.start_calls)
        # The nonce has been durably consumed across app instances.
        previous_raw, previous_headers = self.signed("POST", "/internal/v1/runs", self.start_body(),
                                                      jti="55555555-5555-4555-8555-555555555555")
        self.assertEqual(200, self.client.post("/internal/v1/runs", content=previous_raw,
                                               headers=previous_headers).status_code)
        restarted = TestClient(create_app(keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=self.db,
                                          catalog=self.catalog, executor=self.executor))
        self.assertEqual(401, restarted.post("/internal/v1/runs", content=previous_raw,
                                             headers=previous_headers).status_code)
        raw, headers = self.signed("POST", "/internal/v1/runs", self.start_body())
        self.assertEqual(first.json(), restarted.post("/internal/v1/runs", content=raw,
                                                      headers=headers).json())
        self.assertEqual(1, self.executor.start_calls)

    def test_cross_tenant_and_grant_denial(self):
        self.assertEqual(200, self.send_start().status_code)
        other = str(uuid.uuid4())
        self.assertEqual(404, self.send_get(assertion_changes={"tenant_id": other}).status_code)
        self.assertEqual(403, self.send_get(assertion_changes={"grants": GRANTS[:-1]}).status_code)
        self.assertEqual(403, self.send_get(assertion_changes={"capabilities": ["other"]}).status_code)

    def test_same_thread_different_run_and_paused_resume(self):
        self.executor.pause = True
        self.assertEqual("PAUSED", self.send_start().json()["status"])
        resume_id = str(uuid.uuid4())
        result = self.send_resume(request_id=resume_id)
        self.assertEqual("COMPLETED", result.json()["status"])
        self.assertEqual(result.json(), self.send_resume(request_id=resume_id).json())
        self.assertEqual(1, self.executor.resume_calls)
        self.assertEqual(409, self.send_resume().status_code)
        self.run_id = str(uuid.uuid4())
        self.request_id = str(uuid.uuid4())
        self.assertEqual(409, self.send_start().status_code)

    def test_in_progress_is_observable_and_second_operation_conflicts(self):
        self.executor.block = True
        holder = {}
        worker = threading.Thread(target=lambda: holder.update(response=self.send_start()))
        worker.start()
        self.assertTrue(self.executor.entered.wait(2))
        self.assertEqual("IN_PROGRESS", self.send_get().json()["status"])
        self.request_id = str(uuid.uuid4())
        self.assertEqual(409, self.send_start().status_code)
        self.executor.release.set()
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual("COMPLETED", holder["response"].json()["status"])

    def test_unconfigured_catalog_or_executor_and_inactive_release(self):
        no_catalog = TestClient(create_app(keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=self.db,
                                           catalog=None, executor=self.executor))
        path = "/internal/v1/runs"
        raw, headers = self.signed("POST", path, self.start_body())
        response = no_catalog.post(path, content=raw, headers=headers)
        self.assertEqual((503, "RELEASE_CATALOG_UNAVAILABLE"),
                         (response.status_code, response.json()["error"]["code"]))
        no_executor = TestClient(create_app(keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=self.db,
                                            catalog=self.catalog, executor=None))
        raw, headers = self.signed("POST", path, self.start_body())
        response = no_executor.post(path, content=raw, headers=headers)
        self.assertEqual((503, "ADAPTER_UNAVAILABLE"),
                         (response.status_code, response.json()["error"]["code"]))
        self.catalog.status = "DRAFT"
        self.assertEqual(422, self.send_start().status_code)
        self.assertEqual(0, self.executor.start_calls)

    def test_private_ledger_directory_is_required(self):
        broad = self.db.parent / "broad"
        broad.mkdir(mode=0o755)
        with self.assertRaisesRegex(ValueError, "private"):
            create_app(keyring={TEST_KEY_ID: TEST_KEY}, ledger_path=broad / "bad.sqlite",
                       catalog=self.catalog, executor=self.executor)

    def test_oversized_body_is_rejected_before_dispatch(self):
        path = "/internal/v1/runs"
        raw, headers = self.signed("POST", path, b"x" * (MAX_BODY_BYTES + 1))
        response = self.client.post(path, content=raw, headers=headers)
        self.assertEqual(400, response.status_code)
        self.assertEqual(0, self.executor.start_calls)


if __name__ == "__main__":
    unittest.main()
