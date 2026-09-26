import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const cli = fileURLToPath(new URL("../src/cli.mjs", import.meta.url));
const source = JSON.parse(readFileSync(
  new URL("../../workflow-contracts/examples/hr-policy-qa.json", import.meta.url), "utf8",
));

function graphFromIr(ir) {
  const { schema_version, workflow_id, version, state_schema, types, bindings, execution_policy } = ir;
  return {
    schema_version, workflow_id, version, state_schema, types, bindings, execution_policy,
    nodes: ir.nodes.map((node, index) => ({
      id: node.id, type: node.type, position: { x: index * 100, y: 0 },
      data: {
        type_version: node.type_version,
        input_ports: node.input_ports,
        output_ports: node.output_ports,
        config: node.config,
      },
    })),
    edges: ir.edges.map((edge) => ({
      id: edge.id,
      source: edge.from.node_id,
      target: edge.to.node_id,
      sourceHandle: edge.from.port,
      targetHandle: edge.to.port,
    })),
  };
}

function invoke(input, args = ["convert", "-"]) {
  return spawnSync(process.execPath, [cli, ...args], {
    encoding: "utf8", input, timeout: 5000, maxBuffer: 2 * 1024 * 1024,
  });
}

test("CLI emits validated IR as one JSON line on stdout", () => {
  const run = invoke(JSON.stringify(graphFromIr(source)));
  assert.equal(run.status, 0, run.stderr);
  assert.equal(run.stderr, "");
  const output = JSON.parse(run.stdout);
  assert.equal(output.valid, true);
  assert.equal(output.ir.status, "DRAFT");
  assert.equal(output.publishable, false);
  assert.equal(output.executable, false);
});

test("CLI treats an invalid graph as structured validation result", () => {
  const graph = graphFromIr(source);
  graph.edges[0].targetHandle = "missing";
  const run = invoke(JSON.stringify(graph));
  assert.equal(run.status, 0, run.stderr);
  const output = JSON.parse(run.stdout);
  assert.equal(output.valid, false);
  assert.equal(output.ir, null);
  assert.ok(output.errors.some((item) => item.code === "UNKNOWN_PORT"));
});

test("malformed, oversized, and invalid UTF-8 input fail with no result", () => {
  const malformed = invoke("{");
  assert.equal(malformed.status, 1);
  assert.equal(malformed.stdout, "");
  const oversized = invoke(" ".repeat(1024 * 1024 + 1));
  assert.equal(oversized.status, 1);
  assert.match(oversized.stderr, /exceeds/);
  const invalidUtf8 = spawnSync(process.execPath, [cli, "convert", "-"], {
    input: Buffer.from([0xc3, 0x28]), timeout: 5000,
  });
  assert.equal(invalidUtf8.status, 1);
  assert.equal(invalidUtf8.stdout.length, 0);
});

test("CLI requires the explicit convert stdin command", () => {
  const run = invoke("{}", ["convert"]);
  assert.equal(run.status, 2);
  assert.equal(run.stdout, "");
  assert.match(run.stderr, /Usage:/);
});
