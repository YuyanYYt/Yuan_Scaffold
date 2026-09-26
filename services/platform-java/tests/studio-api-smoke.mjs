import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../../../');
const baseUrl = process.env.BASE_URL ?? 'http://127.0.0.1:18080';
const username = process.env.BASIC_USERNAME;
const password = process.env.BASIC_PASSWORD;
if (!username || !password) throw new Error('Set BASIC_USERNAME and BASIC_PASSWORD for a local test account');

const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
const manifest = JSON.parse(await readFile(resolve(root, 'examples/asset-app/manifest.json'), 'utf8'));
const graph = JSON.parse(await readFile(resolve(root, 'apps/platform-admin/src/workflow-starter.json'), 'utf8'));

async function request(path, { method = 'GET', body, csrf } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      Authorization: authorization,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(csrf ? { Cookie: csrf.cookie, 'X-XSRF-TOKEN': csrf.token } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  return response;
}

async function json(response, expected) {
  const value = await response.json();
  assert.equal(response.status, expected, JSON.stringify(value));
  return value;
}

const me = await json(await request('/api/v1/me'), 200);
assert.ok(me.permissions.includes('studio:project:read'));
assert.ok(me.permissions.includes('studio:project:write'));

const csrfResponse = await request('/api/v1/csrf');
const csrfBody = await json(csrfResponse, 200);
const csrf = { token: csrfBody.token, cookie: csrfResponse.headers.get('set-cookie')?.split(';')[0] };
assert.ok(csrf.token && csrf.cookie?.startsWith('XSRF-TOKEN='));

const created = await json(await request('/api/v1/project-drafts', {
  method: 'POST', body: { name: 'Asset smoke draft', manifest }, csrf,
}), 201);
assert.equal(created.revision, 1);
assert.equal(created.manifest.project.artifactId, 'asset-app');

const draft = await json(await request(`/api/v1/project-drafts/${created.id}`), 200);
assert.equal(draft.id, created.id);
const updated = await json(await request(`/api/v1/project-drafts/${created.id}`, {
  method: 'PUT', body: { name: 'Asset smoke revised', manifest, expectedRevision: 1 }, csrf,
}), 200);
assert.equal(updated.revision, 2);
await json(await request(`/api/v1/project-drafts/${created.id}`, {
  method: 'PUT', body: { name: 'Stale write', manifest, expectedRevision: 1 }, csrf,
}), 409);

const firstPage = await json(await request('/api/v1/project-drafts/page?limit=2'), 200);
assert.equal(firstPage.items.length, 2);
assert.ok(firstPage.nextCursor?.updatedAt && firstPage.nextCursor?.id);
const nextPageQuery = new URLSearchParams({ limit: '2',
  beforeUpdatedAt: firstPage.nextCursor.updatedAt, beforeId: firstPage.nextCursor.id });
const secondPage = await json(await request(`/api/v1/project-drafts/page?${nextPageQuery}`), 200);
assert.ok(secondPage.items.length > 0);
assert.ok(!secondPage.items.some(item => firstPage.items.some(first => first.id === item.id)));

const preview = await json(await request('/api/v1/code-previews', {
  method: 'POST', body: { manifest }, csrf,
}), 200);
assert.equal(preview.previewSchemaVersion, '1.0.0');
assert.ok(preview.files.some(file => file.path.endsWith('/AssetController.java')));

const archiveResponse = await request('/api/v1/code-exports', {
  method: 'POST', body: { manifest }, csrf,
});
assert.equal(archiveResponse.status, 200);
assert.match(archiveResponse.headers.get('content-type') ?? '', /application\/zip/);
assert.equal(archiveResponse.headers.get('x-yuan-source-status'), 'DRAFT_UNVERIFIED');
const exportAuditId = archiveResponse.headers.get('x-yuan-export-audit-id');
assert.match(exportAuditId ?? '', /^[0-9a-f-]{36}$/);
const archive = new Uint8Array(await archiveResponse.arrayBuffer());
assert.deepEqual([...archive.slice(0, 4)], [0x50, 0x4b, 0x03, 0x04]);
const events = await json(await request('/api/v1/audit?limit=100'), 200);
const exportEvent = events.find(event => event.objectId === exportAuditId);
assert.equal(exportEvent?.action, 'CODE_EXPORT');
assert.equal(exportEvent?.outcome, 'SUCCESS');
assert.match(exportEvent?.manifestSha256 ?? '', /^[0-9a-f]{64}$/);

const validated = await json(await request('/api/v1/workflow-drafts/validate', {
  method: 'POST', body: { graph }, csrf,
}), 200);
assert.equal(validated.valid, true);
assert.equal(validated.scope, 'draft_static_only');
assert.equal(validated.ir.status, 'DRAFT');
assert.equal(validated.publishable, false);
assert.equal(validated.executable, false);

const invalidGraph = structuredClone(graph);
invalidGraph.edges[0].sourceHandle = 'not_a_port';
const rejected = await json(await request('/api/v1/workflow-drafts/validate', {
  method: 'POST', body: { graph: invalidGraph }, csrf,
}), 200);
assert.equal(rejected.valid, false);
assert.equal(rejected.ir, null);
assert.ok(rejected.errors.length > 0);

const summary = {
  checkedAt: new Date().toISOString(),
  baseUrl,
  tenantId: me.tenantId,
  draftId: created.id,
  draftRevision: updated.revision,
  paginationSecondPageCount: secondPage.items.length,
  previewFileCount: preview.files.length,
  previewBytes: preview.totalBytes,
  archiveBytes: archive.length,
  archivePath: `yuan-source-${created.id}.zip`,
  exportAuditId,
  workflowId: validated.ir.workflow_id,
  workflowStatus: validated.ir.status,
  workflowScope: validated.scope,
  invalidGraphRejected: rejected.valid === false,
};
const artifactDir = resolve(root, 'artifacts/test-runs/studio-v04');
await mkdir(artifactDir, { recursive: true });
await writeFile(resolve(artifactDir, summary.archivePath), archive);
await writeFile(resolve(artifactDir, 'api-smoke.json'), `${JSON.stringify(summary, null, 2)}\n`);
console.log(JSON.stringify(summary));
