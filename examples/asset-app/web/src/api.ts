export type Credentials = { login: string; password: string };

export type AssetRequest = {
  sku: string;
  name: string;
  quantity: number;
  available: boolean;
};

export type Asset = AssetRequest & { id: string; createdAt: string; updatedAt: string };

function basic(credentials: Credentials): string {
  const bytes = new TextEncoder().encode(credentials.login + ':' + credentials.password);
  return 'Basic ' + btoa(String.fromCharCode(...bytes));
}

export function assetApi(credentials: Credentials) {
  async function csrfToken(): Promise<string> {
    const response = await fetch('/api/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error('CSRF token request failed');
    return (await response.json() as { token: string }).token;
  }

  async function call<T>(path: string, method = 'GET', body?: AssetRequest): Promise<T> {
    const headers: Record<string, string> = { Authorization: basic(credentials) };
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') headers['X-CSRF-TOKEN'] = await csrfToken();
    const response = await fetch(path, {
      method, headers, body: body ? JSON.stringify(body) : undefined, credentials: 'same-origin'
    });
    if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + await response.text());
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  }

  return {
    list: () => call<Asset[]>('/api/assets'),
    get: (id: string) => call<Asset>('/api/assets/' + encodeURIComponent(id)),
    create: (value: AssetRequest) => call<Asset>('/api/assets', 'POST', value),
    update: (id: string, value: AssetRequest) => call<Asset>('/api/assets/' + encodeURIComponent(id), 'PUT', value),
    remove: (id: string) => call<void>('/api/assets/' + encodeURIComponent(id), 'DELETE')
  };
}
