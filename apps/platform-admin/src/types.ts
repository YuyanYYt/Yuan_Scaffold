export type FieldType = 'string' | 'integer' | 'boolean';

export type ManifestField = {
  name: string;
  type: FieldType;
  required: boolean;
  maxLength?: number;
  minimum?: number;
  unique?: boolean;
};

export type Manifest = {
  schemaVersion: '1.0.0';
  generatorVersion: '0.3.0';
  project: {
    groupId: string;
    artifactId: string;
    packageName: string;
  };
  entity: {
    name: string;
    table: string;
    apiPath: string;
    fields: ManifestField[];
    permissions: { read: string; write: string };
  };
};

export type DraftSummary = {
  id: string;
  name: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type Draft = DraftSummary & { manifest: unknown };

export type DraftCursor = { updatedAt: string; id: string };

export type DraftPage = { items: DraftSummary[]; nextCursor: DraftCursor | null };

export type Me = {
  tenantId: string;
  userId: string;
  tenantSlug: string;
  username: string;
  permissions: string[];
};

export type Credentials = { login: string; password: string };

export type EditorValue = { name: string; manifest: Manifest };

export type PreviewFile = { path: string; sha256: string; bytes: number; content: string };

export type CodePreview = {
  previewSchemaVersion: '1.0.0';
  generatorVersion: string;
  manifestSha256: string;
  totalBytes: number;
  files: PreviewFile[];
};

export type StudioGraph = {
  schema_version: string;
  workflow_id: string;
  version: string;
  state_schema: Record<string, unknown>;
  types: Record<string, unknown>;
  bindings: Record<string, unknown>;
  execution_policy: Record<string, unknown>;
  nodes: StudioNode[];
  edges: StudioEdge[];
};

export type StudioPort = { name: string; schema_ref: string; required: boolean };

export type StudioNode = {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: {
    label?: string;
    type_version: string;
    input_ports: StudioPort[];
    output_ports: StudioPort[];
    config: Record<string, unknown>;
  };
  selected?: boolean;
};

export type StudioEdge = {
  id: string;
  source: string;
  target: string;
  sourceHandle: string;
  targetHandle: string;
  selected?: boolean;
};

export type WorkflowValidation = {
  valid: boolean;
  scope: 'draft_static_only';
  publishable: false;
  executable: false;
  errors: Array<{ code: string; path: string; message: string }>;
  ir: Record<string, unknown> | null;
};
