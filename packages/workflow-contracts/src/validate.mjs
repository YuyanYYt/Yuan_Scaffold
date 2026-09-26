import Ajv2020 from "ajv/dist/2020.js";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";

const schema = JSON.parse(
  readFileSync(new URL("../schema/workflow-ir-v0.1.schema.json", import.meta.url), "utf8"),
);
const nodeRegistrySchema = JSON.parse(
  readFileSync(new URL("../schema/node-descriptor-v0.1.schema.json", import.meta.url), "utf8"),
);
const nodeRegistry = JSON.parse(
  readFileSync(new URL("../examples/core-node-registry-v0.1.json", import.meta.url), "utf8"),
);
const canonicalEvidenceSet = JSON.parse(
  readFileSync(new URL("../schema/evidence-set-v0.1.schema.json", import.meta.url), "utf8"),
);
function makeAjv() {
  return new Ajv2020({ allErrors: true, strict: true, strictRequired: false, allowUnionTypes: true });
}
const ajv = makeAjv();
const validateSchema = ajv.compile(schema);
const validateRegistrySchema = ajv.compile(nodeRegistrySchema);
if (!validateRegistrySchema(nodeRegistry)) {
  throw new Error(`Invalid bundled node registry: ${JSON.stringify(validateRegistrySchema.errors)}`);
}
const descriptors = new Map(nodeRegistry.nodes.map((node) => [`${node.type}@${node.type_version}`, node]));

function issue(code, path, message) {
  return { code, path, message };
}

function draftResult(errors) {
  return { valid: errors.length === 0, scope: "draft_static_only", publishable: false, executable: false, errors };
}

function reachable(startIds, adjacency) {
  const seen = new Set(startIds);
  const queue = [...startIds];
  for (let i = 0; i < queue.length; i += 1) {
    for (const next of adjacency.get(queue[i]) ?? []) {
      if (!seen.has(next)) {
        seen.add(next);
        queue.push(next);
      }
    }
  }
  return seen;
}

function reachesAny(startIds, targets, adjacency, excluded = new Set()) {
  const initial = startIds.filter((id) => !excluded.has(id));
  const seen = new Set(initial);
  const queue = [...initial];
  for (let i = 0; i < queue.length; i += 1) {
    const current = queue[i];
    if (targets.has(current)) return true;
    for (const next of adjacency.get(current) ?? []) {
      if (!excluded.has(next) && !seen.has(next)) {
        seen.add(next);
        queue.push(next);
      }
    }
  }
  return false;
}

function hasCycleWithoutLoop(nodes, adjacency) {
  const nonLoop = new Set(nodes.filter((node) => node.type !== "loop").map((node) => node.id));
  const active = new Set();
  const visited = new Set();
  function visit(id) {
    if (active.has(id)) return true;
    if (visited.has(id)) return false;
    visited.add(id);
    active.add(id);
    for (const next of adjacency.get(id) ?? []) {
      if (nonLoop.has(next) && visit(next)) return true;
    }
    active.delete(id);
    return false;
  }
  return [...nonLoop].some((id) => visit(id));
}

function dominators(entryId, nodeIds, predecessors) {
  const all = new Set(nodeIds);
  const result = new Map(nodeIds.map((id) => [id, id === entryId ? new Set([id]) : new Set(all)]));
  let changed = true;
  while (changed) {
    changed = false;
    for (const id of nodeIds) {
      if (id === entryId) continue;
      const parents = [...(predecessors.get(id) ?? [])];
      const common = parents.length > 0 ? new Set(result.get(parents[0])) : new Set();
      for (const parent of parents.slice(1)) {
        for (const candidate of common) {
          if (!result.get(parent).has(candidate)) common.delete(candidate);
        }
      }
      common.add(id);
      const previous = result.get(id);
      if (previous.size !== common.size || [...common].some((item) => !previous.has(item))) {
        result.set(id, common);
        changed = true;
      }
    }
  }
  return result;
}

function typeName(reference) {
  if (typeof reference !== "string") return null;
  return reference.startsWith("#/types/") ? reference.slice("#/types/".length) : reference;
}

function stateSchemaAtPath(stateSchema, statePath) {
  let current = stateSchema;
  for (const segment of statePath.slice(2).split(".")) {
    if (!current?.properties || !Object.hasOwn(current.properties, segment)) return null;
    current = current.properties[segment];
  }
  return current;
}

function fieldHasType(fieldSchema, expected) {
  const types = Array.isArray(fieldSchema.type) ? fieldSchema.type : [fieldSchema.type];
  if (types.length === 1 && (types[0] === expected || expected === "number" && types[0] === "integer")) return true;
  if (Array.isArray(fieldSchema.enum)) {
    return fieldSchema.enum.every((value) => expected === "number" ? typeof value === "number" : typeof value === expected);
  }
  return false;
}

function configStatePaths(value, pointer = "") {
  if (!value || typeof value !== "object") return [];
  const result = [];
  for (const [key, child] of Object.entries(value)) {
    const childPointer = `${pointer}/${key}`;
    if ((key === "state_path" || key.endsWith("_state_path")) && typeof child === "string") {
      result.push({ path: child, pointer: childPointer, key });
    } else {
      result.push(...configStatePaths(child, childPointer));
    }
  }
  return result;
}

export function validateWorkflow(workflow) {
  const errors = [];
  if (!validateSchema(workflow)) {
    for (const error of validateSchema.errors ?? []) {
      errors.push(issue("SCHEMA", error.instancePath || "/", error.message ?? "invalid value"));
    }
    return draftResult(errors);
  }

  if (workflow.status !== "DRAFT") {
    errors.push(issue("DRAFT_ONLY", "/status", "S1 validator checks drafts only; publication requires an authoritative server gate"));
  }

  // User-provided $id values must not leak into subsequent validation calls.
  const payloadAjv = makeAjv();
  for (const [name, payloadSchema] of Object.entries(workflow.types)) {
    try {
      payloadAjv.compile(payloadSchema);
    } catch (error) {
      errors.push(issue("INVALID_PAYLOAD_SCHEMA", `/types/${name}`, error.message));
    }
  }
  try {
    payloadAjv.compile(workflow.state_schema);
  } catch (error) {
    errors.push(issue("INVALID_STATE_SCHEMA", "/state_schema", error.message));
  }

  if (workflow.nodes.some((node) => node.type === "rag_retrieve" || node.type === "answer_with_citation") &&
      !isDeepStrictEqual(workflow.types.EvidenceSet, canonicalEvidenceSet)) {
    errors.push(issue("CANONICAL_EVIDENCE_SCHEMA", "/types/EvidenceSet", "built-in RAG nodes require the bundled EvidenceSet v0.1 payload schema"));
  }

  const nodes = workflow.nodes;
  const edges = workflow.edges;
  const nodeById = new Map();
  const outgoing = new Map();
  const incoming = new Map();
  const successors = new Map();
  const predecessors = new Map();
  const edgeIds = new Set();
  const edgeConnections = new Set();

  for (const [index, node] of nodes.entries()) {
    if (nodeById.has(node.id)) {
      errors.push(issue("DUPLICATE_NODE", `/nodes/${index}/id`, `duplicate node id ${node.id}`));
    }
    nodeById.set(node.id, node);
    const descriptor = descriptors.get(`${node.type}@${node.type_version}`);
    if (!descriptor) {
      errors.push(issue("UNKNOWN_NODE_VERSION", `/nodes/${index}/type_version`, "node type and version are not in the bundled registry"));
    } else {
      for (const [side, key] of [["input", "input_ports"], ["output", "output_ports"]]) {
        const contract = descriptor.port_contract[side];
        const ports = node[key];
        if (ports.length < contract.min_ports ||
            (contract.max_ports !== null && ports.length > contract.max_ports)) {
          errors.push(issue("PORT_SIGNATURE", `/nodes/${index}/${key}`, `${node.type} ${side} port count violates registry contract`));
        }
        if (contract.required_names?.some((name) => !ports.some((port) => port.name === name)) ||
            contract.allowed_names && ports.some((port) => !contract.allowed_names.includes(port.name))) {
          errors.push(issue("PORT_SIGNATURE", `/nodes/${index}/${key}`, `${node.type} ${side} port names violate registry contract`));
        }
        if (contract.required_schema_ref && ports.some((port) => port.schema_ref !== contract.required_schema_ref)) {
          errors.push(issue("PORT_SIGNATURE", `/nodes/${index}/${key}`, `${node.type} ${side} payload type violates registry contract`));
        }
      }
    }
    outgoing.set(node.id, []);
    incoming.set(node.id, []);
    successors.set(node.id, new Set());
    predecessors.set(node.id, new Set());
    for (const side of ["input_ports", "output_ports"]) {
      const names = new Set();
      for (const [portIndex, port] of node[side].entries()) {
        if (names.has(port.name)) {
          errors.push(issue("DUPLICATE_PORT", `/nodes/${index}/${side}/${portIndex}/name`, `duplicate port ${port.name}`));
        }
        names.add(port.name);
        const type = typeName(port.schema_ref);
        if (!Object.hasOwn(workflow.types, type)) {
          errors.push(issue("UNKNOWN_TYPE", `/nodes/${index}/${side}/${portIndex}/schema_ref`, `unknown type ${type}`));
        }
      }
    }
  }

  const entry = nodeById.get(workflow.entry_node_id);
  if (nodes.filter((node) => node.type === "chat_input").length !== 1) {
    errors.push(issue("ENTRY_COUNT", "/nodes", "exactly one chat_input node is required"));
  }
  if (!entry || entry.type !== "chat_input") {
    errors.push(issue("INVALID_ENTRY", "/entry_node_id", "entry must name a chat_input node"));
  }
  const terminals = nodes.filter((node) => node.type === "chat_output");
  if (terminals.length === 0) {
    errors.push(issue("MISSING_TERMINAL", "/nodes", "at least one chat_output node is required"));
  }

  for (const [index, edge] of edges.entries()) {
    if (edgeIds.has(edge.id)) {
      errors.push(issue("DUPLICATE_EDGE", `/edges/${index}/id`, `duplicate edge id ${edge.id}`));
    }
    edgeIds.add(edge.id);
    const connection = `${edge.from.node_id}:${edge.from.port}->${edge.to.node_id}:${edge.to.port}`;
    if (edgeConnections.has(connection)) {
      errors.push(issue("DUPLICATE_CONNECTION", `/edges/${index}`, "duplicate graph connection"));
    }
    edgeConnections.add(connection);
    const source = nodeById.get(edge.from.node_id);
    const target = nodeById.get(edge.to.node_id);
    if (!source || !target) {
      errors.push(issue("DANGLING_EDGE", `/edges/${index}`, "edge endpoint is missing"));
      continue;
    }
    const sourcePort = source.output_ports.find((port) => port.name === edge.from.port);
    const targetPort = target.input_ports.find((port) => port.name === edge.to.port);
    if (!sourcePort || !targetPort) {
      errors.push(issue("UNKNOWN_PORT", `/edges/${index}`, "edge references an undeclared output or input port"));
      continue;
    }
    if (typeName(sourcePort.schema_ref) !== typeName(targetPort.schema_ref)) {
      errors.push(issue("PORT_TYPE_MISMATCH", `/edges/${index}`, `${sourcePort.schema_ref} cannot feed ${targetPort.schema_ref}`));
    }
    outgoing.get(source.id).push(edge);
    incoming.get(target.id).push(edge);
    successors.get(source.id).add(target.id);
    predecessors.get(target.id).add(source.id);
  }

  if (entry && (incoming.get(entry.id)?.length ?? 0) > 0) {
    errors.push(issue("ENTRY_HAS_INPUT", "/entry_node_id", "chat_input entry cannot have incoming edges"));
  }
  for (const terminal of terminals) {
    if ((outgoing.get(terminal.id)?.length ?? 0) > 0) {
      errors.push(issue("TERMINAL_HAS_OUTPUT", `/nodes/${nodes.indexOf(terminal)}`, "chat_output cannot have outgoing edges"));
    }
  }

  for (const [index, node] of nodes.entries()) {
    const arriving = incoming.get(node.id) ?? [];
    const leaving = outgoing.get(node.id) ?? [];
    for (const port of node.output_ports) {
      if (leaving.filter((edge) => edge.from.port === port.name).length > 1) {
        errors.push(issue("AMBIGUOUS_OUTPUT", `/nodes/${index}/output_ports`, `output ${port.name} has multiple edges`));
      }
    }
    for (const port of node.input_ports) {
      const count = arriving.filter((edge) => edge.to.port === port.name).length;
      if (port.required && count === 0 && node.id !== workflow.entry_node_id) {
        errors.push(issue("UNCONNECTED_INPUT", `/nodes/${index}/input_ports`, `required input ${port.name} has no edge`));
      }
      if (count > 1) {
        errors.push(issue("AMBIGUOUS_INPUT", `/nodes/${index}/input_ports`, `input ${port.name} has multiple edges`));
      }
    }
    if (node.type === "branch") {
      const ports = node.output_ports.map((port) => port.name);
      if (!ports.includes("default") || ports.length < 2) {
        errors.push(issue("BRANCH_PORTS", `/nodes/${index}/output_ports`, "branch requires default and at least one case port"));
      }
      for (const port of ports) {
        if (leaving.filter((edge) => edge.from.port === port).length !== 1) {
          errors.push(issue("BRANCH_ROUTE", `/nodes/${index}/output_ports`, `branch port ${port} needs exactly one edge`));
        }
      }
      const declared = node.config.cases.map((item) => item.port);
      if (new Set(declared).size !== declared.length ||
          new Set([...declared, node.config.default_port]).size !== ports.length ||
          [...declared, node.config.default_port].some((port) => !ports.includes(port))) {
        errors.push(issue("BRANCH_CASES", `/nodes/${index}/config/cases`, "case and default ports must match declared output ports exactly"));
      }
      const predicateKeys = node.config.cases.map((item) =>
        `${item.when.op}\u0000${item.when.state_path}\u0000${JSON.stringify(item.when.value)}`);
      if (new Set(predicateKeys).size !== predicateKeys.length) {
        errors.push(issue("DUPLICATE_BRANCH_PREDICATE", `/nodes/${index}/config/cases`, "branch cases must not repeat an identical predicate"));
      }
    }
    if (node.type === "loop") {
      const ports = node.output_ports.map((port) => port.name);
      if (ports.length !== 2 || !ports.includes("next") || !ports.includes("done")) {
        errors.push(issue("LOOP_PORTS", `/nodes/${index}/output_ports`, "loop requires next and done ports"));
      }
      for (const port of ["next", "done"]) {
        if (leaving.filter((edge) => edge.from.port === port).length !== 1) {
          errors.push(issue("LOOP_ROUTE", `/nodes/${index}/output_ports`, `loop port ${port} needs exactly one edge`));
        }
      }
      const next = leaving.find((edge) => edge.from.port === "next");
      const done = leaving.find((edge) => edge.from.port === "done");
      if (next && !reachesAny([next.to.node_id], new Set([node.id]), successors)) {
        errors.push(issue("LOOP_BODY", `/nodes/${index}`, "next route must return to the loop node"));
      }
      if (done && reachesAny([done.to.node_id], new Set([node.id]), successors)) {
        errors.push(issue("LOOP_EXIT", `/nodes/${index}`, "done route must never return to the same loop"));
      }
      if (done && !reachesAny([done.to.node_id], new Set(terminals.map((item) => item.id)), successors, new Set([node.id]))) {
        errors.push(issue("LOOP_EXIT", `/nodes/${index}`, "done route must reach a terminal without revisiting the loop"));
      }
    }
    if (node.type === "human_approval") {
      const ports = node.output_ports.map((port) => port.name);
      if (ports.length !== 3 || ["approved", "rejected", "expired"].some((port) => !ports.includes(port))) {
        errors.push(issue("APPROVAL_PORTS", `/nodes/${index}/output_ports`, "approval requires approved, rejected, and expired ports"));
      }
      for (const port of ["approved", "rejected", "expired"]) {
        if (leaving.filter((edge) => edge.from.port === port).length !== 1) {
          errors.push(issue("APPROVAL_ROUTE", `/nodes/${index}/output_ports`, `approval port ${port} needs exactly one edge`));
        }
      }
    }
  }

  const fromEntry = entry ? reachable([entry.id], successors) : new Set();
  const toTerminal = reachable(terminals.map((node) => node.id), predecessors);
  for (const [index, node] of nodes.entries()) {
    if (!fromEntry.has(node.id)) errors.push(issue("UNREACHABLE_NODE", `/nodes/${index}`, `${node.id} cannot be reached from entry`));
    if (!toTerminal.has(node.id)) errors.push(issue("NO_TERMINAL_PATH", `/nodes/${index}`, `${node.id} cannot reach a chat_output`));
  }
  if (hasCycleWithoutLoop(nodes, successors)) {
    errors.push(issue("UNBOUNDED_CYCLE", "/edges", "every cycle must pass through an explicit loop node"));
  }

  const dom = entry ? dominators(entry.id, nodes.map((node) => node.id), predecessors) : new Map();
  const tools = new Map((workflow.bindings.tools ?? []).map((binding) => [binding.id, binding]));
  const subgraphs = new Map((workflow.bindings.subgraphs ?? []).map((binding) => [binding.id, binding]));
  for (const [group, bindings] of Object.entries(workflow.bindings)) {
    const ids = new Set();
    for (const [index, binding] of bindings.entries()) {
      if (ids.has(binding.id)) {
        errors.push(issue("DUPLICATE_BINDING", `/bindings/${group}/${index}/id`, `duplicate ${group} binding ${binding.id}`));
      }
      ids.add(binding.id);
    }
  }
  const bound = (group, id) => workflow.bindings[group].some((binding) => binding.id === id);
  if (nodes.some((node) => node.type === "answer_with_citation") && workflow.execution_policy.max_model_tokens === 0) {
    errors.push(issue("MODEL_BUDGET_REQUIRED", "/execution_policy/max_model_tokens", "model nodes require a positive token budget"));
  }
  if (nodes.some((node) => node.type === "tool_call") && workflow.execution_policy.max_tool_calls === 0) {
    errors.push(issue("TOOL_BUDGET_REQUIRED", "/execution_policy/max_tool_calls", "Tool nodes require a positive Tool call budget"));
  }
  for (const [index, node] of nodes.entries()) {
    for (const reference of configStatePaths(node.config)) {
      const field = stateSchemaAtPath(workflow.state_schema, reference.path);
      const path = `/nodes/${index}/config${reference.pointer}`;
      if (!field) {
        errors.push(issue("UNKNOWN_STATE_PATH", path, `${reference.path} is not explicitly declared in state_schema`));
      } else if ((reference.key === "query_state_path" || reference.key === "idempotency_key_state_path") &&
                 !fieldHasType(field, "string")) {
        errors.push(issue("STATE_PATH_TYPE", path, `${reference.key} must reference a string field`));
      } else if (reference.key === "state_path" &&
                 (node.type === "branch" || node.type === "loop")) {
        const predicates = node.type === "branch" ? node.config.cases.map((item) => item.when) : [node.config.until];
        if (predicates.some((predicate) => predicate.state_path === reference.path &&
            ["gt", "gte", "lt", "lte"].includes(predicate.op)) && !fieldHasType(field, "number")) {
          errors.push(issue("STATE_PATH_TYPE", path, "ordered comparison requires a numeric state field"));
        }
      }
    }
    if (node.type === "rag_retrieve") {
      if (!bound("knowledge_bases", node.config.knowledge_base_binding_id) ||
          !bound("indexes", node.config.index_binding_id)) {
        errors.push(issue("UNKNOWN_RAG_BINDING", `/nodes/${index}/config`, "RAG knowledge base or index binding is missing"));
      }
      const { strategy, dense_top_k: dense, sparse_top_k: sparse, fusion_top_k: fusion,
        rerank_top_k: rerank, context_top_k: context, ef_search: ef } = node.config.retrieval;
      if (((strategy === "dense" || strategy === "hybrid") && dense < 1) ||
          ((strategy === "sparse" || strategy === "hybrid") && sparse < 1) ||
          rerank > fusion || context > rerank || (ef !== undefined && dense > 0 && ef < dense)) {
        errors.push(issue("INVALID_RETRIEVAL_CONFIG", `/nodes/${index}/config/retrieval`, "retrieval stage counts or ef_search are inconsistent"));
      }
    }
    if (node.type === "answer_with_citation" &&
        (!bound("models", node.config.model_binding_id) || !bound("prompts", node.config.prompt_binding_id))) {
      errors.push(issue("UNKNOWN_ANSWER_BINDING", `/nodes/${index}/config`, "answer model or prompt binding is missing"));
    }
    if (node.type === "human_approval" && !bound("approval_policies", node.config.approval_policy_binding_id)) {
      errors.push(issue("UNKNOWN_APPROVAL_POLICY", `/nodes/${index}/config/approval_policy_binding_id`, "approval policy binding is missing"));
    }
    if (node.type === "tool_call") {
      const binding = tools.get(node.config.binding_id);
      if (!binding) {
        errors.push(issue("UNKNOWN_TOOL_BINDING", `/nodes/${index}/config/binding_id`, "tool binding is missing"));
      } else if (binding.effect !== "read" || binding.risk === "high" || binding.risk === "critical") {
        const approval = nodeById.get(node.config.approval_node_id);
        if (!approval || approval.type !== "human_approval" || !dom.get(node.id)?.has(approval.id)) {
          errors.push(issue("APPROVAL_REQUIRED", `/nodes/${index}/config/approval_node_id`, "write, external, or high-risk tool requires a dominating human_approval node"));
        } else {
          if (approval.config.intent_id !== node.config.binding_id) {
            errors.push(issue("APPROVAL_INTENT", `/nodes/${index}/config/approval_node_id`, "approval intent must match the tool binding"));
          }
          const rejectedStarts = (outgoing.get(approval.id) ?? [])
            .filter((edge) => edge.from.port !== "approved")
            .map((edge) => edge.to.node_id);
          if (reachesAny(rejectedStarts, new Set([node.id]), successors)) {
            errors.push(issue("APPROVAL_BYPASS", `/nodes/${index}/config/approval_node_id`, "non-approved approval outcomes can reach the tool"));
          }
        }
        if (!node.config.idempotency_key_state_path) {
          errors.push(issue("IDEMPOTENCY_REQUIRED", `/nodes/${index}/config/idempotency_key_state_path`, "effectful tool requires an idempotency key state path"));
        }
      }
    }
    if (node.type === "subgraph") {
      const binding = subgraphs.get(node.config.binding_id);
      if (!binding) {
        errors.push(issue("UNKNOWN_SUBGRAPH_BINDING", `/nodes/${index}/config/binding_id`, "subgraph binding is missing"));
      } else if (binding.resource_id === workflow.workflow_id) {
        errors.push(issue("RECURSIVE_SUBGRAPH", `/nodes/${index}/config/binding_id`, "a workflow cannot reference itself as a subgraph"));
      }
    }
    if (node.type === "auth_context" && node.config.source !== "server_context") {
      errors.push(issue("UNTRUSTED_CONTEXT", `/nodes/${index}/config/source`, "auth_context must use server_context"));
    }
  }
  return draftResult(errors);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const path = process.argv[2];
  if (!path) {
    process.stderr.write("Usage: node src/validate.mjs <workflow.json>\n");
    process.exitCode = 2;
  } else {
    try {
      const workflow = JSON.parse(readFileSync(path, "utf8"));
      const result = validateWorkflow(workflow);
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      if (!result.valid) process.exitCode = 1;
    } catch (error) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 2;
    }
  }
}
