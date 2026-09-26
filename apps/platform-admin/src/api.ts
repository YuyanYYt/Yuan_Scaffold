import type { CodePreview, Credentials, Draft, DraftCursor, DraftPage, DraftSummary, EditorValue, Manifest,
  Me, StudioGraph, WorkflowValidation } from './types';

type ApiErrorBody = { code?: string; message?: string };

export class ApiRequestError extends Error {
  public readonly status: number;
  public readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = code;
  }
}

function authorization(credentials: Credentials): string {
  const bytes = new TextEncoder().encode(`${credentials.login}:${credentials.password}`);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return `Basic ${btoa(binary)}`;
}

export function platformApi(credentials: Credentials) {
  const auth = authorization(credentials);

  async function parse<T>(response: Response): Promise<T> {
    if (!response.ok) {
      await fail(response);
    }
    return await response.json() as T;
  }

  async function fail(response: Response): Promise<never> {
    let body: ApiErrorBody | null = null;
    try { body = await response.json() as ApiErrorBody; } catch { /* keep status */ }
    const message = body?.message || response.statusText || '服务端未返回错误说明';
    throw new ApiRequestError(response.status, body?.code || 'HTTP_ERROR',
      `HTTP ${response.status} · ${message}`);
  }

  async function csrf(): Promise<{ headerName: string; token: string }> {
    return parse(await fetch('/api/v1/csrf', {
      headers: { Authorization: auth }, credentials: 'same-origin'
    }));
  }

  async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Authorization: auth };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') {
      const token = await csrf();
      headers[token.headerName] = token.token;
    }
    return parse(await fetch(path, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin'
    }));
  }

  async function exportCode(manifest: Manifest): Promise<Blob> {
    const token = await csrf();
    const response = await fetch('/api/v1/code-exports', {
      method: 'POST', credentials: 'same-origin',
      headers: { Authorization: auth, 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({ manifest })
    });
    if (!response.ok) await fail(response);
    if (!(response.headers.get('Content-Type') || '').toLowerCase().includes('application/zip')) {
      throw new Error('服务端没有返回 application/zip，源码导出未完成。');
    }
    if (response.headers.get('X-Yuan-Source-Status') !== 'DRAFT_UNVERIFIED') {
      throw new Error('服务端没有标注源码草稿的构建状态，已拒绝下载。');
    }
    return await response.blob();
  }

  return {
    me: () => request<Me>('/api/v1/me'),
    listDrafts: () => request<DraftSummary[]>('/api/v1/project-drafts'),
    listDraftPage: async (limit = 30, before?: DraftCursor) => {
      const query = new URLSearchParams({ limit: String(limit) });
      if (before) {
        query.set('beforeUpdatedAt', before.updatedAt);
        query.set('beforeId', before.id);
      }
      const page = await request<DraftPage>(`/api/v1/project-drafts/page?${query}`);
      return { ...page, nextCursor: page.nextCursor ?? null };
    },
    getDraft: (id: string) => request<Draft>(`/api/v1/project-drafts/${encodeURIComponent(id)}`),
    createDraft: (value: EditorValue) => request<Draft>('/api/v1/project-drafts', 'POST',
      { name: value.name.trim(), manifest: value.manifest }),
    updateDraft: (id: string, value: EditorValue, expectedRevision: number) =>
      request<Draft>(`/api/v1/project-drafts/${encodeURIComponent(id)}`, 'PUT',
        { name: value.name.trim(), manifest: value.manifest, expectedRevision }),
    previewCode: (manifest: Manifest) => request<CodePreview>('/api/v1/code-previews', 'POST',
      { manifest }),
    exportCode,
    validateWorkflow: (graph: StudioGraph) =>
      request<WorkflowValidation>('/api/v1/workflow-drafts/validate', 'POST', { graph })
  };
}
