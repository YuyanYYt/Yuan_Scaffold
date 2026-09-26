import { readFileSync } from 'node:fs';

const tsType = { string: 'string', integer: 'number', boolean: 'boolean' };
const cap = name => name[0].toUpperCase() + name.slice(1);

function api(m) {
  const e = m.entity;
  const fields = e.fields.map(f => `  ${f.name}${f.required ? '' : '?'}: ${tsType[f.type]}${f.required ? '' : ' | null'};`).join('\n');
  return `export type Credentials = { login: string; password: string };

export type ${e.name}Request = {
${fields}
};

export type ${e.name} = ${e.name}Request & { id: string; createdAt: string; updatedAt: string };

function basic(credentials: Credentials): string {
  const bytes = new TextEncoder().encode(credentials.login + ':' + credentials.password);
  return 'Basic ' + btoa(String.fromCharCode(...bytes));
}

export function ${e.name[0].toLowerCase() + e.name.slice(1)}Api(credentials: Credentials) {
  async function csrfToken(): Promise<string> {
    const response = await fetch('/api/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error('CSRF token request failed');
    return (await response.json() as { token: string }).token;
  }

  async function call<T>(path: string, method = 'GET', body?: ${e.name}Request): Promise<T> {
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
    list: () => call<${e.name}[]>('${e.apiPath}'),
    get: (id: string) => call<${e.name}>('${e.apiPath}/' + encodeURIComponent(id)),
    create: (value: ${e.name}Request) => call<${e.name}>('${e.apiPath}', 'POST', value),
    update: (id: string, value: ${e.name}Request) => call<${e.name}>('${e.apiPath}/' + encodeURIComponent(id), 'PUT', value),
    remove: (id: string) => call<void>('${e.apiPath}/' + encodeURIComponent(id), 'DELETE')
  };
}
`;
}

function page(m) {
  const e = m.entity;
  const apiName = e.name[0].toLowerCase() + e.name.slice(1) + 'Api';
  const initial = e.fields.map(f => `  ${f.name}: ${f.type === 'string' ? "''" : f.type === 'boolean' ? 'false' : '0'},`).join('\n');
  const inputFields = e.fields.map(f => {
    const label = cap(f.name);
    if (f.type === 'boolean') return `        <label><input type="checkbox" checked={Boolean(form.${f.name})} onChange={event => setForm({ ...form, ${f.name}: event.target.checked })} /> ${label}</label>`;
    const inputType = f.type === 'integer' ? 'number' : 'text';
    const coercion = f.type === 'integer' ? 'Number(event.target.value)' : 'event.target.value';
    const min = f.minimum !== undefined ? ` min={${f.minimum}}` : '';
    const max = f.maxLength !== undefined ? ` maxLength={${f.maxLength}}` : '';
    return `        <label>${label}<input type="${inputType}" value={form.${f.name} ?? ''}${min}${max} required={${f.required}} onChange={event => setForm({ ...form, ${f.name}: ${coercion} })} /></label>`;
  }).join('\n');
  const tableHeaders = e.fields.map(f => `            <th>${cap(f.name)}</th>`).join('\n');
  const tableCells = e.fields.map(f => `              <td>{String(item.${f.name} ?? '')}</td>`).join('\n');
  return `import { useState } from 'react';
import { ${apiName}, type ${e.name}, type ${e.name}Request } from './api';

const emptyForm: ${e.name}Request = {
${initial}
};

export default function ${e.name}Page() {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [items, setItems] = useState<${e.name}[]>([]);
  const [form, setForm] = useState<${e.name}Request>(emptyForm);
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState('');
  const client = () => ${apiName}({ login, password });

  async function refresh() {
    try { setItems(await client().list()); setError(''); }
    catch (cause) { setError(String(cause)); }
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      if (editing) await client().update(editing, form);
      else await client().create(form);
      setEditing(null); setForm(emptyForm); await refresh();
    } catch (cause) { setError(String(cause)); }
  }

  async function remove(id: string) {
    if (!window.confirm('Delete this record?')) return;
    try { await client().remove(id); await refresh(); }
    catch (cause) { setError(String(cause)); }
  }

  return <main>
    <h1>${e.name} register</h1>
    <p>Local demo login format: tenant/username. Credentials stay in page memory.</p>
    <section className="credentials">
      <label>Login<input value={login} autoComplete="username" onChange={event => setLogin(event.target.value)} /></label>
      <label>Password<input type="password" value={password} autoComplete="current-password" onChange={event => setPassword(event.target.value)} /></label>
      <button onClick={refresh}>Load</button>
    </section>
    {error && <p role="alert" className="error">{error}</p>}
    <form onSubmit={save}>
      <h2>{editing ? 'Edit' : 'Add'} ${e.name}</h2>
${inputFields}
      <button type="submit">{editing ? 'Update' : 'Create'}</button>
      {editing && <button type="button" onClick={() => { setEditing(null); setForm(emptyForm); }}>Cancel</button>}
    </form>
    <div className="table-scroll"><table><thead><tr>
${tableHeaders}
      <th>Actions</th>
    </tr></thead><tbody>
      {items.map(item => <tr key={item.id}>
${tableCells}
        <td>
          <button onClick={() => { setEditing(item.id); setForm(item); }}>Edit</button>
          <button onClick={() => remove(item.id)}>Delete</button>
        </td>
      </tr>)}
    </tbody></table></div>
  </main>;
}
`;
}

export function renderWeb(m) {
  const e = m.entity;
  const lock = JSON.parse(readFileSync(new URL('../templates/web-package-lock.json', import.meta.url), 'utf8'));
  lock.name = `${m.project.artifactId}-web`;
  lock.packages[''].name = lock.name;
  const files = new Map([
    ['web/package.json', `${JSON.stringify({
      name: `${m.project.artifactId}-web`, version: '0.3.0', private: true, type: 'module',
      engines: { node: '^20.19.0 || >=22.12.0' },
      scripts: { dev: 'vite', build: 'tsc --noEmit && vite build' },
      dependencies: { react: '19.3.0', 'react-dom': '19.3.0' },
      devDependencies: { '@types/react': '19.3.0', '@types/react-dom': '19.3.0', typescript: '5.9.3', vite: '8.3.1' }
    }, null, 2)}\n`],
    ['web/package-lock.json', `${JSON.stringify(lock, null, 2)}\n`],
    ['web/tsconfig.json', `${JSON.stringify({ compilerOptions: { target: 'ES2022', useDefineForClassFields: true, lib: ['ES2022', 'DOM', 'DOM.Iterable'], module: 'ESNext', skipLibCheck: true, moduleResolution: 'Bundler', allowImportingTsExtensions: true, resolveJsonModule: true, isolatedModules: true, noEmit: true, jsx: 'react-jsx', strict: true }, include: ['src', 'vite.config.ts'] }, null, 2)}\n`],
    ['web/vite.config.ts', `import { defineConfig, loadEnv } from 'vite';\n\nexport default defineConfig(({ mode }) => {\n  const env = loadEnv(mode, '.', 'APP_');\n  return { server: { proxy: { '/api': env.APP_API_TARGET || 'http://localhost:8082' } } };\n});\n`],
    ['web/.env.example', 'APP_API_TARGET=http://localhost:8082\n'],
    ['web/index.html', `<!doctype html>\n<html lang="en"><head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" /><title>${e.name} register</title></head><body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body></html>\n`],
    ['web/src/api.ts', api(m)],
    [`web/src/${e.name}Page.tsx`, page(m)],
    ['web/src/main.tsx', `import React from 'react';\nimport { createRoot } from 'react-dom/client';\nimport ${e.name}Page from './${e.name}Page';\nimport './style.css';\n\ncreateRoot(document.getElementById('root')!).render(<React.StrictMode><${e.name}Page /></React.StrictMode>);\n`],
    ['web/src/style.css', `:root { font-family: system-ui, sans-serif; color: #18212d; background: #f6f8fa; } body { margin: 0; } main { max-width: 1050px; margin: 2rem auto; padding: 2rem; background: white; border-radius: 12px; } section, form { display: flex; gap: 1rem; flex-wrap: wrap; margin: 1rem 0; } form h2 { flex-basis: 100%; margin-bottom: 0; } label { display: grid; gap: .25rem; } input { padding: .45rem; } button { padding: .45rem .75rem; cursor: pointer; } .table-scroll { max-width: 100%; overflow-x: auto; } table { width: 100%; min-width: 650px; border-collapse: collapse; } th, td { border-bottom: 1px solid #ddd; padding: .6rem; text-align: left; white-space: nowrap; } .error { color: #ad1234; }\n`]
  ]);
  return files;
}
