import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { convertStudioGraph } from "../src/convert.mjs";

const sourceIr = JSON.parse(readFileSync(
  new URL("../../workflow-contracts/examples/hr-policy-qa.json", import.meta.url), "utf8",
));
const advancedIr = JSON.parse(readFileSync(
  new URL("../../workflow-contracts/examples/hr-advanced-flow.json", import.meta.url), "utf8",
));

function studioGraph(inputIr = sourceIr) {
  const source = structuredClone(inputIr);
  const { schema_version, workflow_id, version, state_schema, types, bindings, execution_policy } = source;
  return {
    schema_version, workflow_id, version, state_schema, types, bindings, execution_policy,
    nodes: source.nodes.map((node, index) => ({
      id: node.id,
      type: node.type,
      position: { x: index * 180, y: index % 2 * 80 },
      data: {
        type_version: node.type_version,
        input_ports: node.input_ports,
        output_ports: node.output_ports,
        config: node.config,
        label: `Node ${index}`,
      },
    })),
    edges: source.edges.map((edge) => ({
      id: edge.id,
      source: edge.from.node_id,
      target: edge.to.node_id,
      sourceHandle: edge.from.port,
      targetHandle: edge.to.port,
    })),
  };
}

function expectIssue(graph, code) {
  const result = convertStudioGraph(graph);
  assert.equal(result.valid, false);
  assert.equal(result.ir, null);
  assert.equal(result.publishable, false);
  assert.equal(result.executable, false);
  assert.ok(result.errors.some((item) => item.code === code), JSON.stringify(result.errors));
}

test("React Flow graph converts to the existing Canonical Workflow IR draft", () => {
  const graph = studioGraph();
  const result = convertStudioGraph(graph);
  assert.equal(result.valid, true, JSON.stringify(result.errors));
  assert.equal(result.scope, "draft_static_only");
  assert.equal(result.publishable, false);
  assert.equal(result.executable, false);
  assert.deepEqual(result.errors, []);
  const { ui_metadata, ...semantics } = result.ir;
  assert.deepEqual(semantics, sourceIr);
  assert.deepEqual(ui_metadata.nodes.chat_in, { position: { x: 0, y: 0 }, label: "Node 0" });
  assert.deepEqual(result.ir.edges[0], sourceIr.edges[0]);
});

test("branch, loop, approval and tool port routes preserve advanced workflow semantics", () => {
  const result = convertStudioGraph(studioGraph(advancedIr));
  assert.equal(result.valid, true, JSON.stringify(result.errors));
  const { ui_metadata, ...semantics } = result.ir;
  assert.deepEqual(semantics, advancedIr);
  assert.equal(ui_metadata.nodes.chat_in.position.x, 0);
});

test("position and selection changes only affect UI metadata, without mutating input", () => {
  const graph = studioGraph();
  const first = convertStudioGraph(graph);
  graph.nodes[0].position = { x: 900, y: -30 };
  graph.nodes[0].selected = true;
  graph.edges[0].animated = true;
  graph.edges[0].label = "request";
  const second = convertStudioGraph(graph);
  assert.equal(first.valid, true);
  assert.equal(second.valid, true);
  assert.notDeepEqual(first.ir.ui_metadata, second.ir.ui_metadata);
  const { ui_metadata: firstMetadata, ...firstSemantics } = first.ir;
  const { ui_metadata: secondMetadata, ...secondSemantics } = second.ir;
  assert.deepEqual(firstSemantics, secondSemantics);
  assert.deepEqual(firstMetadata.nodes.chat_in.position, { x: 0, y: 0 });
  graph.nodes[0].data.config.source = "untrusted";
  assert.deepEqual(first.ir.nodes[0].config, { source: "user_message" });
});

test("missing handle is rejected instead of guessing a single-port connection", () => {
  const graph = studioGraph();
  delete graph.edges[0].sourceHandle;
  expectIssue(graph, "UI_SHAPE");
});

test("client release and authorization fields cannot enter the IR", () => {
  const graph = studioGraph();
  graph.status = "ACTIVE";
  expectIssue(graph, "UI_SHAPE");
  delete graph.status;
  graph.nodes[1].data.principal_id = "forged-user";
  expectIssue(graph, "UI_SHAPE");
  delete graph.nodes[1].data.principal_id;
  graph.edges[0].data = { capability: "tool.write" };
  expectIssue(graph, "UI_SHAPE");
});

test("contract validator rejects invalid port and disconnected graph", () => {
  const graph = studioGraph();
  graph.edges[0].targetHandle = "missing_input";
  expectIssue(graph, "UNKNOWN_PORT");
  graph.edges[0].targetHandle = "request";
  graph.edges[0].target = "retrieve_policy";
  graph.edges[0].targetHandle = "authorized_request";
  expectIssue(graph, "PORT_TYPE_MISMATCH");
});

test("contract validator rejects unknown node type and unsupported schema version", () => {
  const graph = studioGraph();
  graph.nodes[1].type = "arbitrary_executor";
  expectIssue(graph, "SCHEMA");
  graph.nodes[1].type = "auth_context";
  graph.schema_version = "9.0.0";
  expectIssue(graph, "SCHEMA");
});

test("duplicate graph IDs and removed entry do not produce partial IR", () => {
  const graph = studioGraph();
  graph.edges[1].id = graph.edges[0].id;
  expectIssue(graph, "DUPLICATE_EDGE");
  graph.edges[1].id = sourceIr.edges[1].id;
  graph.nodes[0].type = "chat_output";
  expectIssue(graph, "UI_ENTRY");
});

test("non-JSON and malformed canvas properties fail closed", () => {
  const graph = studioGraph();
  graph.nodes[0].position.x = Number.NaN;
  expectIssue(graph, "UI_JSON");
  graph.nodes[0].position.x = 0;
  graph.nodes[0].data.config.source = () => "user_message";
  expectIssue(graph, "UI_JSON");
  graph.nodes[0].data.config.source = "user_message";
  graph.nodes[0].position.z = 1;
  expectIssue(graph, "UI_SHAPE");
});

test("oversized node collection returns a bounded static validation result", () => {
  const graph = studioGraph();
  graph.nodes = Array.from({ length: 50_000 }, () => null);
  const result = convertStudioGraph(graph);
  assert.equal(result.valid, false);
  assert.equal(result.ir, null);
  assert.equal(result.errors[0].code, "UI_SIZE");
  assert.ok(Buffer.byteLength(JSON.stringify(result)) < 4096);
});
