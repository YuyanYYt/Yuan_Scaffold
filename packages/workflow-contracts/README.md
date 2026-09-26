# Workflow IR v0.1

This package is the first executable contract for a Yuan Scaffold workflow. The JSON Schema describes the portable graph shape; `src/validate.mjs` adds graph checks that JSON Schema alone cannot express. The initial example is a versioned HR policy question answering flow. This is a contract and validator, not a LangGraph runtime or a released generated application.

## Run

```bash
cd packages/workflow-contracts
npm ci
npm test
npm run validate:example
```

The package pins Ajv in `package-lock.json` to validate JSON Schema draft 2020-12. Node.js 20 or newer is required. JSON Schema is the language neutral contract consumed later by Java, Python, and Studio; the Java and Python adapters must run the same fixtures when implemented. `schema/node-descriptor-v0.1.schema.json` defines the node registry contract, and `examples/core-node-registry-v0.1.json` declares eleven built-in node signatures. Their lifecycle is `declared`; this package contains no node executor.

## Contract boundaries

- `schema_version` identifies the IR structure; workflow `version`, node `type_version`, and resource binding versions have separate lifecycles.
- UI coordinates and presentation state belong in `ui_metadata`. Compilers ignore them when deciding execution semantics.
- A workflow declares needed resources and capabilities. Runtime identity, tenant, user, thread ACL, grants, credentials, and approval decisions come from trusted services at execution time. Tool binding declarations are rechecked against the authoritative Catalog.
- A released workflow version is immutable. S1 only validates drafts; publishing must add an immutable release record, dependency resolution, evaluation evidence, and a canonical semantic hash.
- Index build settings such as HNSW `M` and `efConstruction` live in a versioned index binding. Retrieval `top_k` and `ef_search` settings are workflow/RAG configuration and are validated against the selected backend before release.
- A port's `required` flag means the graph must contain a wire to that input. Branches and approvals select one route at runtime; this flag does not mean every incoming branch payload is present on every execution.
- Built-in RAG nodes use the fixed `EvidenceSet` v0.1 payload contract in `schema/evidence-set-v0.1.schema.json`. Custom evidence shapes require a new node or payload version; redefining the same name does not change the built-in signature.
- Branch cases are evaluated in declaration order and the first matching case wins; `default` is used when none match. The draft validator rejects identical case predicates. The later LangGraph compiler must preserve this order.

## Validation and version policy

`validateWorkflow` returns `scope: "draft_static_only"` and always returns `publishable: false` and `executable: false`, even when `valid` is true. It checks the draft shape, bundled node versions and port signatures, graph connections, reachability, bounded loop declarations, branch/approval routing, explicit state paths, resource references, RAG stage counts, and declared high-risk Tool paths. State paths that depend on dynamic schemas are rejected until a versioned resolver exists. It cannot trust Tool effect or risk claims in imported IR. A future server publication gate must resolve the authoritative Tool Catalog and enforce runtime authorization, approval freshness, complete subgraph dependencies, side effect idempotency, and backend-specific index parameter support.

Unknown execution fields and node types fail validation. Compatible additions to v0.1 require an explicit default and Golden Case regression. A breaking field or semantic change requires a new major `schema_version`; migration must preserve the original IR, before/after hashes, compiler version, and test evidence. Unsupported versions are rejected rather than guessed. The first implementation stages are tracked in [the project map](../../ProjectDocs/项目总地图.md).
