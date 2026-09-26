import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { validateWorkflow } from "../src/validate.mjs";

const hrExample = JSON.parse(
  readFileSync(new URL("../examples/hr-policy-qa.json", import.meta.url), "utf8"),
);
const advancedExample = JSON.parse(
  readFileSync(new URL("../examples/hr-advanced-flow.json", import.meta.url), "utf8"),
);

function cloneExample() {
  return structuredClone(hrExample);
}

function cloneAdvanced() {
  return structuredClone(advancedExample);
}

function expectIssue(workflow, code) {
  const result = validateWorkflow(workflow);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((item) => item.code === code), JSON.stringify(result.errors));
}

test("HR policy workflow passes schema and graph validation", () => {
  assert.deepEqual(validateWorkflow(cloneExample()), {
    valid: true, scope: "draft_static_only", publishable: false, executable: false, errors: [],
  });
});

test("bounded branching, loop, subgraph, and approved write Tool pass validation", () => {
  assert.deepEqual(validateWorkflow(cloneAdvanced()), {
    valid: true, scope: "draft_static_only", publishable: false, executable: false, errors: [],
  });
});

test("unknown execution fields fail closed", () => {
  const workflow = cloneExample();
  workflow.tenant_id = "forged-tenant";
  expectIssue(workflow, "SCHEMA");
});

test("unsupported IR versions fail closed", () => {
  const workflow = cloneExample();
  workflow.schema_version = "9.0.0";
  expectIssue(workflow, "SCHEMA");
});

test("release states are outside the draft validator scope", () => {
  const workflow = cloneExample();
  workflow.status = "ACTIVE";
  expectIssue(workflow, "DRAFT_ONLY");
});

test("unknown built-in node implementation versions are rejected", () => {
  const workflow = cloneExample();
  workflow.nodes.find((node) => node.type === "answer_gate").type_version = "9.9.9";
  expectIssue(workflow, "UNKNOWN_NODE_VERSION");
});

test("unresolved nested payload references are rejected", () => {
  const workflow = cloneExample();
  workflow.types.UserMessage = { $ref: "#/$defs/NotHere" };
  expectIssue(workflow, "INVALID_PAYLOAD_SCHEMA");
});

test("payload schema IDs do not make validation depend on earlier calls", () => {
  const workflow = cloneExample();
  workflow.types.UserMessage.$id = "https://example.test/user-message";
  workflow.state_schema.$id = "https://example.test/workflow-state";
  assert.equal(validateWorkflow(structuredClone(workflow)).valid, true);
  assert.equal(validateWorkflow(structuredClone(workflow)).valid, true);
});

test("duplicate node IDs are rejected", () => {
  const workflow = cloneExample();
  workflow.nodes.push(structuredClone(workflow.nodes.at(-1)));
  expectIssue(workflow, "DUPLICATE_NODE");
});

test("dangling edge endpoints are rejected", () => {
  const workflow = cloneExample();
  workflow.edges[0].to.node_id = "missing-node";
  expectIssue(workflow, "DANGLING_EDGE");
});

test("unknown edge ports are rejected", () => {
  const workflow = cloneExample();
  workflow.edges[0].to.port = "missing-port";
  expectIssue(workflow, "UNKNOWN_PORT");
});

test("unreachable nodes are rejected", () => {
  const workflow = cloneExample();
  const orphan = structuredClone(workflow.nodes.at(-1));
  orphan.id = "orphan-output";
  workflow.nodes.push(orphan);
  expectIssue(workflow, "UNREACHABLE_NODE");
});

test("branch cannot omit a default route", () => {
  const workflow = cloneAdvanced();
  workflow.edges = workflow.edges.filter((edge) => edge.id !== "e_route_approval");
  expectIssue(workflow, "BRANCH_ROUTE");
});

test("branch rejects identical predicates on different routes", () => {
  const workflow = cloneAdvanced();
  const branch = workflow.nodes.find((node) => node.type === "branch");
  branch.output_ports.push({ name: "also_read", schema_ref: "#/types/Control", required: true });
  branch.config.cases.push({ port: "also_read", when: structuredClone(branch.config.cases[0].when) });
  workflow.edges.push({ id: "duplicate_case_route", from: { node_id: branch.id, port: "also_read" }, to: { node_id: "read_tool", port: "in" } });
  expectIssue(workflow, "DUPLICATE_BRANCH_PREDICATE");
});

test("a cycle bypassing the explicit loop is rejected", () => {
  const workflow = cloneAdvanced();
  workflow.edges.push({
    id: "unbounded_return",
    from: { node_id: "read_tool", port: "result" },
    to: { node_id: "route", port: "in" },
  });
  expectIssue(workflow, "UNBOUNDED_CYCLE");
});

test("a loop done route cannot return to its own loop", () => {
  const workflow = cloneAdvanced();
  workflow.edges.find((edge) => edge.id === "e_loop_done").to = { node_id: "bounded_loop", port: "repeat" };
  expectIssue(workflow, "LOOP_EXIT");
});

test("a write Tool needs a dominating approval", () => {
  const workflow = cloneAdvanced();
  workflow.nodes.find((node) => node.id === "write_tool").config.approval_node_id = "route";
  expectIssue(workflow, "APPROVAL_REQUIRED");
});

test("a rejected approval outcome cannot reach a write Tool", () => {
  const workflow = cloneAdvanced();
  workflow.edges.find((edge) => edge.id === "e_approval_rejected").to = { node_id: "write_tool", port: "in" };
  expectIssue(workflow, "APPROVAL_BYPASS");
});

test("approval intent must match the Tool binding", () => {
  const workflow = cloneAdvanced();
  workflow.nodes.find((node) => node.id === "approve_write").config.intent_id = "read_balance";
  expectIssue(workflow, "APPROVAL_INTENT");
});

test("write Tools need an idempotency key path", () => {
  const workflow = cloneAdvanced();
  delete workflow.nodes.find((node) => node.id === "write_tool").config.idempotency_key_state_path;
  expectIssue(workflow, "IDEMPOTENCY_REQUIRED");
});

test("self-recursive subgraph bindings are rejected", () => {
  const workflow = cloneAdvanced();
  workflow.bindings.subgraphs[0].resource_id = workflow.workflow_id;
  expectIssue(workflow, "RECURSIVE_SUBGRAPH");
});

test("RAG stage counts and ef_search are checked", () => {
  const workflow = cloneExample();
  workflow.nodes.find((node) => node.type === "rag_retrieve").config.retrieval.rerank_top_k = 99;
  expectIssue(workflow, "INVALID_RETRIEVAL_CONFIG");
});

test("RAG evidence ports keep their registered payload type", () => {
  const workflow = cloneExample();
  workflow.nodes.find((node) => node.type === "rag_retrieve").output_ports[0].schema_ref = "#/types/UserMessage";
  workflow.nodes.find((node) => node.type === "answer_with_citation").input_ports[0].schema_ref = "#/types/UserMessage";
  expectIssue(workflow, "PORT_SIGNATURE");
});

test("RAG evidence cannot be redefined as a different payload structure", () => {
  const workflow = cloneExample();
  workflow.types.EvidenceSet = { type: "string" };
  expectIssue(workflow, "CANONICAL_EVIDENCE_SCHEMA");
});

test("configuration paths must name explicit state fields with compatible types", () => {
  const workflow = cloneExample();
  workflow.nodes.find((node) => node.type === "rag_retrieve").config.query_state_path = "$.missing_query";
  expectIssue(workflow, "UNKNOWN_STATE_PATH");

  const advanced = cloneAdvanced();
  advanced.nodes.find((node) => node.type === "branch").config.cases[0].when.state_path = "$.missing_route_field";
  expectIssue(advanced, "UNKNOWN_STATE_PATH");

  const wrongType = cloneExample();
  wrongType.nodes.find((node) => node.type === "rag_retrieve").config.query_state_path = "$.evidence";
  expectIssue(wrongType, "STATE_PATH_TYPE");
});

test("model and Tool nodes need nonzero global budgets", () => {
  const modelWorkflow = cloneExample();
  modelWorkflow.execution_policy.max_model_tokens = 0;
  expectIssue(modelWorkflow, "MODEL_BUDGET_REQUIRED");
  const toolWorkflow = cloneAdvanced();
  toolWorkflow.execution_policy.max_tool_calls = 0;
  expectIssue(toolWorkflow, "TOOL_BUDGET_REQUIRED");
});

test("draft validation never authorizes a self-declared Tool risk", () => {
  const workflow = cloneAdvanced();
  const binding = workflow.bindings.tools.find((tool) => tool.id === "submit_leave");
  binding.effect = "read";
  binding.risk = "low";
  binding.required_capabilities = [];
  const result = validateWorkflow(workflow);
  assert.equal(result.valid, true);
  assert.equal(result.publishable, false);
  assert.equal(result.executable, false);
});

test("unregistered extension nodes fail closed", () => {
  const workflow = cloneExample();
  workflow.nodes.find((node) => node.type === "answer_gate").type = "ext.demo.custom";
  expectIssue(workflow, "UNKNOWN_NODE_VERSION");
});
