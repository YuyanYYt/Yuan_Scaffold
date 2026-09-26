import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiRequestError, platformApi } from '../src/api.ts';

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' }
  });
}

const credentials = { login: 'alpha/editor', password: 'test-password-123' };

test('draft mutations bind Basic identity, CSRF header and optimistic revision', async t => {
  const calls = [];
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url === '/api/v1/csrf') return json({ headerName: 'X-XSRF-TOKEN', token: 'csrf-value' });
    return json({ id: 'draft-1', revision: 2, name: 'Example', manifest: {} });
  };

  await platformApi(credentials).updateDraft('draft-1', { name: 'Example', manifest: {} }, 1);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].url, '/api/v1/csrf');
  assert.equal(calls[1].url, '/api/v1/project-drafts/draft-1');
  assert.equal(calls[1].options.method, 'PUT');
  assert.equal(calls[1].options.headers['X-XSRF-TOKEN'], 'csrf-value');
  assert.equal(calls[1].options.credentials, 'same-origin');
  assert.equal(JSON.parse(calls[1].options.body).expectedRevision, 1);
  assert.equal(atob(calls[1].options.headers.Authorization.slice(6)), 'alpha/editor:test-password-123');
});

test('server conflict is propagated; no success is synthesized', async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async url => url === '/api/v1/csrf'
    ? json({ headerName: 'X-XSRF-TOKEN', token: 'csrf-value' })
    : json({ code: 'CONFLICT', message: 'Project draft revision is stale' }, 409);

  await assert.rejects(platformApi(credentials).updateDraft('draft-1', { name: 'Example', manifest: {} }, 1),
    error => error instanceof ApiRequestError && error.status === 409 && error.code === 'CONFLICT');
});

test('draft pages use a bounded cursor request without CSRF and normalize the last page', async t => {
  const calls = [];
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return json({ items: [{ id: 'older-draft', name: 'Older' }] });
  };
  const before = { updatedAt: '2026-09-26T04:36:38Z', id: '00000000-0000-0000-0000-000000000001' };
  const page = await platformApi(credentials).listDraftPage(30, before);
  const url = new URL(calls[0].url, 'http://localhost');
  assert.equal(url.pathname, '/api/v1/project-drafts/page');
  assert.equal(url.searchParams.get('limit'), '30');
  assert.equal(url.searchParams.get('beforeUpdatedAt'), before.updatedAt);
  assert.equal(url.searchParams.get('beforeId'), before.id);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(atob(calls[0].options.headers.Authorization.slice(6)), 'alpha/editor:test-password-123');
  assert.equal(page.items[0].id, 'older-draft');
  assert.equal(page.nextCursor, null);
});

test('source draft ZIP requires MIME type and unverified status header', async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  let responseType = 'application/zip';
  let sourceStatus = 'DRAFT_UNVERIFIED';
  globalThis.fetch = async url => {
    if (url === '/api/v1/csrf') return json({ headerName: 'X-XSRF-TOKEN', token: 'csrf-value' });
    return new Response(new Uint8Array([0x50, 0x4b]), { status: 200,
      headers: { 'Content-Type': responseType, 'X-Yuan-Source-Status': sourceStatus } });
  };
  const zip = await platformApi(credentials).exportCode({});
  assert.equal(zip.size, 2);
  responseType = 'text/html';
  await assert.rejects(platformApi(credentials).exportCode({}), /application\/zip/);
  responseType = 'application/zip';
  sourceStatus = 'BUILT';
  await assert.rejects(platformApi(credentials).exportCode({}), /构建状态/);
});

test('workflow graph is sent whole to server validation without client-side success fallback', async t => {
  const calls = [];
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url === '/api/v1/csrf') return json({ headerName: 'X-XSRF-TOKEN', token: 'csrf-value' });
    return json({ valid: false, scope: 'draft_static_only', publishable: false,
      executable: false, errors: [{ code: 'UNKNOWN_PORT', path: '/edges/0', message: 'unknown port' }], ir: null });
  };
  const graph = { schema_version: '0.1.0', nodes: [{ id: 'chat_in' }], edges: [] };
  const result = await platformApi(credentials).validateWorkflow(graph);
  assert.equal(calls[1].url, '/api/v1/workflow-drafts/validate');
  assert.deepEqual(JSON.parse(calls[1].options.body), { graph });
  assert.equal(result.valid, false);
  assert.equal(result.ir, null);
});
