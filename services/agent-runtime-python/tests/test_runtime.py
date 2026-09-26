"""Test-only synthetic adapters; never imported by production code."""

from __future__ import annotations

import json
import time
import unittest
import uuid
from dataclasses import dataclass
from pathlib import Path

from yuan_agent_runtime import (
    AdapterResult, AgentRuntime, AuthContext, PreflightError,
    RuntimeExecutionError, preflight,
)


EXAMPLE = Path(__file__).resolve().parents[3] / "packages/workflow-contracts/examples/hr-policy-qa.json"
ADVANCED = EXAMPLE.with_name("hr-advanced-flow.json")


@dataclass(frozen=True)
class TrustedTestContext:
    tenant: str
    principal: str


class TestOnlyAuthorization:
    def __init__(self):
        self.calls = 0
        self.binding_calls = 0
        self.deny = False
        self.return_false = False

    def authorize(self, trusted_request_context):
        self.calls += 1
        if self.deny or not isinstance(trusted_request_context, TrustedTestContext):
            raise PermissionError("test-only auth rejected context")
        return AuthContext(trusted_request_context.tenant, trusted_request_context.principal, ("hr.read",))

    def require_binding(self, auth, group, binding):
        self.binding_calls += 1
        if self.deny or auth.tenant_id != "tenant-a":
            raise PermissionError("test-only resource denial")
        if group not in {"knowledge_bases", "indexes", "models", "prompts"} or not binding["resource_id"]:
            raise PermissionError("test-only invalid binding")
        if self.return_false:
            return False
        return True


class TestOnlyRetrieval:
    def __init__(self, *, bad=False, cost=20, sleep_seconds=0):
        self.calls = 0
        self.bad = bad
        self.cost = cost
        self.sleep_seconds = sleep_seconds

    def retrieve(self, *, query, knowledge_base, index, config, auth, remaining_ms):
        self.calls += 1
        assert remaining_ms > 0 and config["ef_search"] == 64
        assert knowledge_base["id"] == "hr_policy_kb" and index["kind"] == "hnsw"
        if self.sleep_seconds:
            time.sleep(self.sleep_seconds)
        if self.bad:
            return AdapterResult({"items": [{"citation_id": "c1", "text": "missing source version"}]},
                                 tokens_used=4, cost_micro=self.cost)
        return AdapterResult({"items": [{
            "citation_id": "c1", "source_version": "v1",
            "text": f"TEST SYNTHETIC evidence for {query}: do not use in production",
        }]}, tokens_used=4, cost_micro=self.cost)


class TestOnlyModel:
    def __init__(self, *, tokens=10, cost=30, bad_citation=False):
        self.calls = 0
        self.tokens = tokens
        self.cost = cost
        self.bad_citation = bad_citation

    def generate(self, *, message, evidence, model, prompt, auth, max_tokens, remaining_ms):
        self.calls += 1
        assert max_tokens > 0 and remaining_ms > 0
        assert model["id"] == "answer_model" and prompt["id"] == "answer_prompt"
        ids = ["unknown"] if self.bad_citation else ["c1"]
        return AdapterResult({"text": "TEST SYNTHETIC answer", "citation_ids": ids},
                             tokens_used=self.tokens, cost_micro=self.cost)


class TestOnlyGate:
    def __init__(self):
        self.calls = 0

    def evaluate(self, *, draft, evidence, checks, auth, remaining_ms):
        self.calls += 1
        assert checks == ("groundedness", "citation", "policy")
        assert remaining_ms > 0
        return AdapterResult({"status": "answered", "text": draft["text"],
                              "citation_ids": draft["citation_ids"]})


def ir_document():
    return json.loads(EXAMPLE.read_text(encoding="utf-8"))


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        test_run = Path(__file__).resolve().parents[1] / "artifacts/test-runs" / str(uuid.uuid4())
        test_run.mkdir(parents=True)
        self.db = test_run / "checkpoints.sqlite"
        self.auth = TestOnlyAuthorization()
        self.retrieve = TestOnlyRetrieval()
        self.model = TestOnlyModel()
        self.gate = TestOnlyGate()
        self.context = TrustedTestContext("tenant-a", "alice")

    def runtime(self, document=None):
        return AgentRuntime(
            ir_document() if document is None else document,
            checkpoint_path=self.db, authorization=self.auth,
            retrieval=self.retrieve, model=self.model, answer_gate=self.gate,
        )

    def test_compiles_and_executes_all_six_real_langgraph_nodes(self):
        result = self.runtime().start(thread_id="t1", user_message="请问假期政策？",
                                      trusted_request_context=self.context)
        self.assertEqual("completed", result.status)
        self.assertEqual(6, result.steps)
        self.assertEqual("answered", result.answer["status"])
        self.assertEqual(["chat_input", "auth_context", "rag_retrieve",
                          "answer_with_citation", "answer_gate", "chat_output"],
                         [item["node_type"] for item in result.trace])
        self.assertEqual(14, result.total_tokens)
        self.assertEqual(50, result.cost_micro)
        self.assertEqual(4, self.auth.binding_calls)
        # The trace is metadata only, even though checkpoint state has evidence.
        serialized = json.dumps(result.trace, ensure_ascii=False)
        for secret in ("假期政策", "TEST SYNTHETIC evidence", "tenant-a", "alice"):
            self.assertNotIn(secret, serialized)

    def test_sqlite_resume_after_retrieval_without_repeating_it(self):
        first = self.runtime().start(thread_id="paused", user_message="policy?",
                                     trusted_request_context=self.context,
                                     pause_after_retrieval=True)
        self.assertEqual("paused", first.status)
        self.assertEqual(3, first.steps)
        self.assertIsNone(first.answer)
        self.assertEqual(1, self.retrieve.calls)
        # A fresh Python runtime object and SQLite connection reconstruct state.
        second = self.runtime().resume(thread_id="paused", trusted_request_context=self.context)
        self.assertEqual("completed", second.status)
        self.assertEqual(6, second.steps)
        self.assertEqual(1, self.retrieve.calls)
        self.assertEqual(2, self.auth.calls)
        self.assertEqual(8, self.auth.binding_calls)
        with self.assertRaisesRegex(RuntimeExecutionError, "no paused checkpoint"):
            self.runtime().resume(thread_id="paused", trusted_request_context=self.context)

    def test_missing_adapters_and_forged_identity_fail_closed(self):
        with self.assertRaisesRegex(RuntimeExecutionError, "all production adapters"):
            AgentRuntime(ir_document(), checkpoint_path=self.db)
        with self.assertRaisesRegex(RuntimeExecutionError, "user_message must"):
            self.runtime().start(thread_id="spoof", user_message={"text": "hi", "tenant_id": "tenant-a"},
                                 trusted_request_context=self.context)
        with self.assertRaisesRegex(RuntimeExecutionError, "authorization denied"):
            self.runtime().start(thread_id="spoof", user_message="hi",
                                 trusted_request_context={"tenant": "tenant-a", "principal": "alice"})
        self.assertEqual(0, self.retrieve.calls)

    def test_resource_denial_and_identity_isolation_on_resume(self):
        paused = self.runtime().start(thread_id="x", user_message="hi",
                                      trusted_request_context=self.context,
                                      pause_after_retrieval=True)
        self.assertEqual("paused", paused.status)
        with self.assertRaisesRegex(RuntimeExecutionError, "no paused checkpoint"):
            self.runtime().resume(thread_id="x", trusted_request_context=TrustedTestContext("tenant-a", "bob"))
        self.auth.deny = True
        with self.assertRaisesRegex(RuntimeExecutionError, "authorization denied"):
            self.runtime().resume(thread_id="x", trusted_request_context=self.context)
        self.auth.deny = False
        self.auth.return_false = True
        with self.assertRaisesRegex(RuntimeExecutionError, "authorization denied"):
            self.runtime().resume(thread_id="x", trusted_request_context=self.context)

    def test_checkpoint_rejects_changed_workflow_and_mutated_ir(self):
        self.runtime().start(thread_id="x", user_message="hi",
                             trusted_request_context=self.context, pause_after_retrieval=True)
        altered = ir_document()
        altered["nodes"][2]["config"]["retrieval"]["context_top_k"] = 2
        with self.assertRaisesRegex(RuntimeExecutionError, "checkpoint workflow"):
            self.runtime(altered).resume(thread_id="x", trusted_request_context=self.context)
        runtime = self.runtime()
        runtime.ir.nodes[2]["config"]["retrieval"]["context_top_k"] = 2
        with self.assertRaisesRegex(RuntimeExecutionError, "workflow changed after compilation"):
            runtime.start(thread_id="new", user_message="hi", trusted_request_context=self.context)

    def test_invalid_evidence_fails_before_model_and_does_not_leak(self):
        self.retrieve.bad = True
        result = self.runtime().start(thread_id="bad", user_message="secret question",
                                      trusted_request_context=self.context)
        self.assertEqual("failed", result.status)
        self.assertEqual("node_failure", result.stop_reason)
        self.assertIsNone(result.answer)
        self.assertEqual(0, self.model.calls)
        self.assertEqual(4, result.total_tokens)
        self.assertEqual(20, result.cost_micro)
        self.assertNotIn("secret question", json.dumps(result.trace))

    def test_invalid_model_citation_still_charges_reported_usage(self):
        self.model.bad_citation = True
        result = self.runtime().start(thread_id="bad-citation", user_message="hi",
                                      trusted_request_context=self.context)
        self.assertEqual("failed", result.status)
        self.assertEqual(14, result.total_tokens)
        self.assertEqual(50, result.cost_micro)
        self.assertEqual(0, self.gate.calls)

    def test_budget_stop_discards_over_budget_answer(self):
        document = ir_document()
        document["execution_policy"]["max_model_tokens"] = 8
        result = self.runtime(document).start(thread_id="budget", user_message="hi",
                                              trusted_request_context=self.context)
        self.assertEqual("stopped", result.status)
        self.assertEqual("max_model_tokens", result.stop_reason)
        self.assertIsNone(result.answer)
        self.assertEqual(0, self.gate.calls)

    def test_step_duration_and_zero_cost_budgets(self):
        document = ir_document()
        document["execution_policy"]["max_steps"] = 3
        result = self.runtime(document).start(thread_id="steps", user_message="hi",
                                              trusted_request_context=self.context)
        self.assertEqual(("stopped", "max_steps", 3), (result.status, result.stop_reason, result.steps))
        self.assertEqual(0, self.model.calls)

        document = ir_document()
        document["execution_policy"]["max_duration_ms"] = 5
        self.retrieve = TestOnlyRetrieval(sleep_seconds=0.015)
        result = self.runtime(document).start(thread_id="duration", user_message="hi",
                                              trusted_request_context=self.context)
        self.assertEqual(("stopped", "max_duration_ms"), (result.status, result.stop_reason))
        self.assertEqual(0, self.model.calls)

        document = ir_document()
        document["execution_policy"]["max_cost"]["amount_micro"] = 0
        self.retrieve = TestOnlyRetrieval(cost=0)
        self.model = TestOnlyModel(cost=0)
        result = self.runtime(document).start(thread_id="free", user_message="hi",
                                              trusted_request_context=self.context)
        self.assertEqual("completed", result.status)
        self.assertEqual(0, result.cost_micro)

    def test_narrow_preflight_rejects_unsupported_and_invalid_contract(self):
        document = ir_document()
        document["version"] = "bad version with spaces"
        with self.assertRaises(PreflightError):
            preflight(document)
        document = ir_document()
        document["bindings"]["models"][0]["version"] = "!"
        with self.assertRaises(PreflightError):
            preflight(document)
        document = ir_document()
        document["nodes"][3]["type"] = "tool_call"
        with self.assertRaises(PreflightError):
            preflight(document)
        document = ir_document()
        document["edges"][2]["to"]["port"] = "wrong"
        with self.assertRaises(PreflightError):
            preflight(document)
        document = ir_document()
        document["state_schema"]["not"] = {}
        with self.assertRaises(PreflightError):
            preflight(document)
        with self.assertRaises(PreflightError):
            preflight(json.loads(ADVANCED.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
