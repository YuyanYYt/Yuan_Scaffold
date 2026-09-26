import { useState } from 'react';
import { assetApi, type Asset, type AssetRequest } from './api';

const emptyForm: AssetRequest = {
  sku: '',
  name: '',
  quantity: 0,
  available: false,
};

export default function AssetPage() {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [items, setItems] = useState<Asset[]>([]);
  const [form, setForm] = useState<AssetRequest>(emptyForm);
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState('');
  const client = () => assetApi({ login, password });

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
    <h1>Asset register</h1>
    <p>Local demo login format: tenant/username. Credentials stay in page memory.</p>
    <section className="credentials">
      <label>Login<input value={login} autoComplete="username" onChange={event => setLogin(event.target.value)} /></label>
      <label>Password<input type="password" value={password} autoComplete="current-password" onChange={event => setPassword(event.target.value)} /></label>
      <button onClick={refresh}>Load</button>
    </section>
    {error && <p role="alert" className="error">{error}</p>}
    <form onSubmit={save}>
      <h2>{editing ? 'Edit' : 'Add'} Asset</h2>
        <label>Sku<input type="text" value={form.sku ?? ''} maxLength={64} required={true} onChange={event => setForm({ ...form, sku: event.target.value })} /></label>
        <label>Name<input type="text" value={form.name ?? ''} maxLength={160} required={true} onChange={event => setForm({ ...form, name: event.target.value })} /></label>
        <label>Quantity<input type="number" value={form.quantity ?? ''} min={0} required={true} onChange={event => setForm({ ...form, quantity: Number(event.target.value) })} /></label>
        <label><input type="checkbox" checked={Boolean(form.available)} onChange={event => setForm({ ...form, available: event.target.checked })} /> Available</label>
      <button type="submit">{editing ? 'Update' : 'Create'}</button>
      {editing && <button type="button" onClick={() => { setEditing(null); setForm(emptyForm); }}>Cancel</button>}
    </form>
    <div className="table-scroll"><table><thead><tr>
            <th>Sku</th>
            <th>Name</th>
            <th>Quantity</th>
            <th>Available</th>
      <th>Actions</th>
    </tr></thead><tbody>
      {items.map(item => <tr key={item.id}>
              <td>{String(item.sku ?? '')}</td>
              <td>{String(item.name ?? '')}</td>
              <td>{String(item.quantity ?? '')}</td>
              <td>{String(item.available ?? '')}</td>
        <td>
          <button onClick={() => { setEditing(item.id); setForm(item); }}>Edit</button>
          <button onClick={() => remove(item.id)}>Delete</button>
        </td>
      </tr>)}
    </tbody></table></div>
  </main>;
}
