# Studio Graph → Workflow IR (v0.4 draft slice)

`convertStudioGraph` is a pure JSON adapter from a restricted React Flow node/edge shape to the existing Canonical Workflow IR v0.1. It calls `workflow-contracts`' `validateWorkflow` and returns no IR on failure. It does not call a browser, backend, model, Tool, or resource catalog.

## API

```js
import { convertStudioGraph } from "./src/convert.mjs";

const result = convertStudioGraph({
  schema_version: "0.1.0",
  workflow_id: "example.chat",
  version: "0.1.0",
  state_schema, types, bindings, execution_policy,
  nodes: [{
    id: "chat_in",
    type: "chat_input",                 // Canonical node type, not a renderer name
    position: { x: 20, y: 40 },
    data: {
      type_version: "0.1.0",
      input_ports: [],
      output_ports: [{ name: "request", schema_ref: "#/types/UserMessage", required: true }],
      config: { source: "user_message" },
      label: "User message",             // presentation only
    },
  }],
  edges: [{
    id: "e_chat_auth",
    source: "chat_in",
    sourceHandle: "request",            // explicit output port name
    target: "auth",
    targetHandle: "request",            // explicit input port name
  }],
});

// result: { valid, scope: "draft_static_only", publishable: false,
//           executable: false, errors, ir: valid ? CanonicalIR : null }
```

The snippet shows the data mapping; a valid graph also needs the remaining nodes, edges, payload types, state schema, bindings, and execution policy required by the Canonical IR. For a complete valid graph, the tests adapt `workflow-contracts/examples/hr-policy-qa.json` into this React Flow shape and compare the execution fields byte for byte as JSON objects.

The node `type` is the Canonical node type. `data` carries only `type_version`, `input_ports`, `output_ports`, `config`, and optional visual `label`. The edge handles must equal declared port names; null or missing handles are rejected even for a single-port node. Selection, dragging, width/height, edge animation and labels are recognized presentation fields. Coordinates and labels are stored only in `ir.ui_metadata`; selection, dragging and animation are transient and are omitted. Unknown fields fail closed so a forged release status, identity, grant, or executor cannot slip through this adapter.

`ir.status` is always `DRAFT`. A valid result is **not** executable or publishable. The existing validator checks the draft schema, node registry, ports, graph topology and declared bindings; only a trusted server can resolve Catalog resources, authorization, approvals and immutable release evidence. This package has no inverse IR-to-canvas loader or production publication gate.

## Test

The local contract package must have its dependencies installed first:

```bash
cd packages/workflow-contracts && npm ci
cd ../studio-graph && npm test
```

## Server-side CLI

`workflow-contracts` currently loads schemas with Node's `node:fs`, so this adapter must run in Node. A web UI can post its graph JSON to its own backend; the backend can invoke the CLI with fixed arguments and capture one JSON result line:

```bash
node packages/studio-graph/src/cli.mjs convert - < graph.json
```

Standard input is limited to 1 MiB of valid UTF-8 JSON. A well-formed but invalid graph exits `0` with `{valid:false,errors,ir:null}` on stdout. Invalid JSON, invalid UTF-8, or size overflow exits `1` and writes a diagnostic to stderr; wrong arguments exit `2`. The host service must set its own process timeout and must treat the returned IR as an untrusted draft until a separate trusted server publication gate runs.

This restricted v0.4 adapter accepts at most 256 nodes and 1,024 edges. Invalid results report at most 100 detailed errors plus a truncation notice, with bounded paths and messages, so a large malformed graph cannot turn a validation response into an unbounded service output.
