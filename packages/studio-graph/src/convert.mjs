import { validateWorkflow } from "../../workflow-contracts/src/validate.mjs";

const DRAFT_SCOPE = "draft_static_only";
const MAX_NODES = 256;
const MAX_EDGES = 1024;
const MAX_ERRORS = 100;

function issue(code, path, message) {
  return { code, path: String(path).slice(0, 256), message: String(message).slice(0, 256) };
}

function failure(errors) {
  const shown = errors.slice(0, MAX_ERRORS);
  if (errors.length > shown.length) {
    shown.push(issue("UI_ERROR_LIMIT", "/", `${errors.length - shown.length} additional errors omitted`));
  }
  return {
    valid: false,
    scope: DRAFT_SCOPE,
    publishable: false,
    executable: false,
    errors: shown,
    ir: null,
  };
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function pointer(parent, key) {
  return `${parent}/${String(key).replaceAll("~", "~0").replaceAll("/", "~1")}`;
}

// Studio accepts JSON data. Copy it before validation so the returned IR cannot
// inherit caller-owned references or silently drop non-JSON execution fields.
function copyJson(value, path = "", ancestors = new WeakSet()) {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value !== "object") throw new TypeError(`${path || "/"} must contain JSON values`);
  const prototype = Object.getPrototypeOf(value);
  if (!Array.isArray(value) && prototype !== Object.prototype && prototype !== null) {
    throw new TypeError(`${path || "/"} must contain plain JSON objects`);
  }
  if (ancestors.has(value)) throw new TypeError(`${path || "/"} contains a cycle`);
  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      return Array.from({ length: value.length }, (_, index) =>
        copyJson(value[index], pointer(path, index), ancestors));
    }
    const entries = Reflect.ownKeys(value).map((key) => {
      if (typeof key !== "string") throw new TypeError(`${path || "/"} has a symbol key`);
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (!Object.hasOwn(descriptor, "value")) {
        throw new TypeError(`${pointer(path, key)} must be a data property`);
      }
      return [key, copyJson(descriptor.value, pointer(path, key), ancestors)];
    });
    return Object.fromEntries(entries);
  } finally {
    ancestors.delete(value);
  }
}

function checkFields(value, path, required, optional, errors) {
  if (!isRecord(value)) {
    errors.push(issue("UI_SHAPE", path, "expected an object"));
    return false;
  }
  const allowed = new Set([...required, ...optional]);
  for (const key of required) {
    if (!Object.hasOwn(value, key)) {
      errors.push(issue("UI_SHAPE", pointer(path, key), "required field is missing"));
    }
  }
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) {
      errors.push(issue("UI_SHAPE", pointer(path, key), "unsupported field"));
    }
  }
  return true;
}

function checkOptionalBoolean(value, path, errors) {
  if (value !== undefined && typeof value !== "boolean") {
    errors.push(issue("UI_SHAPE", path, "expected a boolean"));
  }
}

function checkOptionalDimension(value, path, errors) {
  if (value !== undefined && (typeof value !== "number" || !Number.isFinite(value) || value < 0)) {
    errors.push(issue("UI_SHAPE", path, "expected a non-negative finite number"));
  }
}

/**
 * Convert a restricted React Flow graph into a validated Canonical Workflow IR
 * draft. No UI field is treated as authorization or as a release decision.
 * Invalid input returns errors and no IR; it does not produce a partial graph.
 */
export function convertStudioGraph(input) {
  let graph;
  try {
    graph = copyJson(input);
  } catch (error) {
    return failure([issue("UI_JSON", "/", error.message)]);
  }
  const errors = [];
  if (!checkFields(graph, "", [
    "schema_version", "workflow_id", "version", "state_schema", "types",
    "bindings", "execution_policy", "nodes", "edges",
  ], [], errors)) return failure(errors);
  if (!Array.isArray(graph.nodes)) errors.push(issue("UI_SHAPE", "/nodes", "expected an array"));
  if (!Array.isArray(graph.edges)) errors.push(issue("UI_SHAPE", "/edges", "expected an array"));
  if (errors.length > 0) return failure(errors);
  if (graph.nodes.length > MAX_NODES || graph.edges.length > MAX_EDGES) {
    return failure([issue("UI_SIZE", "/", `graph exceeds ${MAX_NODES} nodes or ${MAX_EDGES} edges`)]);
  }

  const nodes = [];
  const edgeRecords = [];
  const nodeMetadata = Object.create(null);
  const edgeMetadata = Object.create(null);

  graph.nodes.forEach((node, index) => {
    const path = `/nodes/${index}`;
    if (!checkFields(node, path, ["id", "type", "position", "data"],
      ["selected", "dragging", "width", "height"], errors)) return;
    checkFields(node.position, `${path}/position`, ["x", "y"], [], errors);
    checkFields(node.data, `${path}/data`,
      ["type_version", "input_ports", "output_ports", "config"], ["label"], errors);
    if (typeof node.id !== "string" || typeof node.type !== "string") {
      errors.push(issue("UI_SHAPE", path, "node id and type must be strings"));
    }
    if (isRecord(node.position) &&
        (![node.position.x, node.position.y].every((value) => typeof value === "number" && Number.isFinite(value)))) {
      errors.push(issue("UI_SHAPE", `${path}/position`, "coordinates must be finite numbers"));
    }
    if (isRecord(node.data) && node.data.label !== undefined && typeof node.data.label !== "string") {
      errors.push(issue("UI_SHAPE", `${path}/data/label`, "label must be a string"));
    }
    checkOptionalBoolean(node.selected, `${path}/selected`, errors);
    checkOptionalBoolean(node.dragging, `${path}/dragging`, errors);
    checkOptionalDimension(node.width, `${path}/width`, errors);
    checkOptionalDimension(node.height, `${path}/height`, errors);
    if (!isRecord(node.data) || !isRecord(node.position) || typeof node.id !== "string") return;
    nodes.push({
      id: node.id,
      type: node.type,
      type_version: node.data.type_version,
      input_ports: node.data.input_ports,
      output_ports: node.data.output_ports,
      config: node.data.config,
    });
    nodeMetadata[node.id] = {
      position: node.position,
      ...(node.data.label === undefined ? {} : { label: node.data.label }),
      ...(node.width === undefined ? {} : { width: node.width }),
      ...(node.height === undefined ? {} : { height: node.height }),
    };
  });

  graph.edges.forEach((edge, index) => {
    const path = `/edges/${index}`;
    if (!checkFields(edge, path,
      ["id", "source", "target", "sourceHandle", "targetHandle"],
      ["label", "selected", "animated"], errors)) return;
    for (const key of ["id", "source", "target", "sourceHandle", "targetHandle"]) {
      if (typeof edge[key] !== "string" || edge[key].length === 0) {
        errors.push(issue("UI_SHAPE", `${path}/${key}`, "expected a non-empty string"));
      }
    }
    if (edge.label !== undefined && typeof edge.label !== "string") {
      errors.push(issue("UI_SHAPE", `${path}/label`, "label must be a string"));
    }
    checkOptionalBoolean(edge.selected, `${path}/selected`, errors);
    checkOptionalBoolean(edge.animated, `${path}/animated`, errors);
    if (typeof edge.id !== "string") return;
    edgeRecords.push({
      id: edge.id,
      from: { node_id: edge.source, port: edge.sourceHandle },
      to: { node_id: edge.target, port: edge.targetHandle },
    });
    if (edge.label !== undefined) edgeMetadata[edge.id] = { label: edge.label };
  });
  if (errors.length > 0) return failure(errors);

  const entries = nodes.filter((node) => node.type === "chat_input");
  if (entries.length !== 1) {
    return failure([issue("UI_ENTRY", "/nodes", "exactly one chat_input node is required")]);
  }
  const ir = {
    schema_version: graph.schema_version,
    workflow_id: graph.workflow_id,
    version: graph.version,
    status: "DRAFT",
    entry_node_id: entries[0].id,
    state_schema: graph.state_schema,
    types: graph.types,
    bindings: graph.bindings,
    execution_policy: graph.execution_policy,
    nodes,
    edges: edgeRecords,
    ui_metadata: { nodes: nodeMetadata, edges: edgeMetadata },
  };
  const validation = validateWorkflow(ir);
  if (!validation.valid) return failure(validation.errors);
  return {
    ...validation,
    publishable: false,
    executable: false,
    ir: validation.valid ? ir : null,
  };
}
