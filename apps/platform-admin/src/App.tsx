import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiRequestError, platformApi } from './api';
import { blankEditor, blankField, cloneEditor, isEditableManifest, validateEditor } from './manifest';
import WorkflowEditor, { starterGraph } from './WorkflowEditor';
import type { CodePreview, Credentials, Draft, DraftCursor, DraftSummary, EditorValue, FieldType, ManifestField, Me, StudioGraph } from './types';

type Page = 'model' | 'workflow';
type LoadState = 'idle' | 'connecting' | 'loading' | 'saving';

function errorText(cause: unknown): string {
  if (cause instanceof ApiRequestError) {
    if (cause.status === 401) return '登录失败：请检查租户/用户名和密码。';
    if (cause.status === 403) return '权限不足：当前账户缺少该接口需要的权限。';
    if (cause.status === 409) return '版本冲突：服务端草稿已被修改。当前表单未覆盖服务端，请核对后重新加载。';
    return cause.message;
  }
  if (cause instanceof TypeError) return '无法连接平台 API。请确认 Java 服务正在运行，以及 APP_API_TARGET 指向正确地址。';
  return cause instanceof Error ? cause.message : String(cause);
}

function dateOnly(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN');
}

function mutableManifest(value: EditorValue): EditorValue {
  return cloneEditor(value);
}

function Label({ children, hint }: { children: React.ReactNode; hint?: string }) {
  return <div className="field-caption"><span>{children}</span>{hint && <small>{hint}</small>}</div>;
}

function FieldCard({ field, index, canWrite, onChange, onRemove }: {
  field: ManifestField;
  index: number;
  canWrite: boolean;
  onChange: (change: Partial<ManifestField>) => void;
  onRemove: () => void;
}) {
  function changeType(type: FieldType) {
    if (type === 'string') onChange({ type, maxLength: 120, minimum: undefined });
    else if (type === 'integer') onChange({ type, maxLength: undefined, minimum: 0, unique: undefined });
    else onChange({ type, maxLength: undefined, minimum: undefined, unique: undefined });
  }

  return <article className="field-card">
    <div className="field-card-title">
      <span className="field-index">{String(index + 1).padStart(2, '0')}</span>
      <strong>{field.name || '未命名字段'}</strong>
      <span className="field-type-pill">{field.type}</span>
      <button type="button" className="text-button danger" disabled={!canWrite} onClick={onRemove}
        aria-label={`移除字段 ${field.name || index + 1}`}>移除</button>
    </div>
    <div className="field-grid">
      <label><Label hint="Java 属性名 / 表字段来源">字段名</Label>
        <input value={field.name} disabled={!canWrite} placeholder="例如: displayName"
          onChange={event => onChange({ name: event.target.value })} /></label>
      <label><Label>数据类型</Label>
        <select value={field.type} disabled={!canWrite}
          onChange={event => changeType(event.target.value as FieldType)}>
          <option value="string">字符串</option><option value="integer">整数</option><option value="boolean">布尔值</option>
        </select></label>
      {field.type === 'string' && <label><Label>最大长度</Label>
        <input type="number" min="1" max="1024" value={field.maxLength ?? ''} disabled={!canWrite}
          onChange={event => onChange({ maxLength: Number(event.target.value) })} /></label>}
      {field.type === 'integer' && <label><Label hint="可选的非负下限">最小值</Label>
        <input type="number" min="0" max="2147483647" value={field.minimum ?? ''} disabled={!canWrite}
          onChange={event => onChange({ minimum: event.target.value === '' ? undefined : Number(event.target.value) })} /></label>}
    </div>
    <div className="field-options">
      <label className="check-line"><input type="checkbox" checked={field.required} disabled={!canWrite}
        onChange={event => onChange({ required: event.target.checked,
          unique: event.target.checked ? field.unique : undefined })} />必填</label>
      {field.type === 'string' && <label className="check-line">
        <input type="checkbox" checked={Boolean(field.unique)} disabled={!canWrite || !field.required}
          onChange={event => onChange({ unique: event.target.checked })} />租户内唯一</label>}
      {field.type === 'string' && !field.required && <span className="muted tiny">唯一字段必须同时为必填</span>}
    </div>
  </article>;
}

export default function App() {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [drafts, setDrafts] = useState<DraftSummary[]>([]);
  const [nextDraftCursor, setNextDraftCursor] = useState<DraftCursor | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [revision, setRevision] = useState<number | null>(null);
  const [editor, setEditor] = useState<EditorValue | null>(null);
  const [unsupported, setUnsupported] = useState<Draft | null>(null);
  const [savedSnapshot, setSavedSnapshot] = useState('');
  const [page, setPage] = useState<Page>('model');
  const [loadState, setLoadState] = useState<LoadState>('idle');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [attempted, setAttempted] = useState(false);
  const [search, setSearch] = useState('');
  const [preview, setPreview] = useState<{ result: CodePreview; input: string } | null>(null);
  const [previewPath, setPreviewPath] = useState('');
  const [previewBusy, setPreviewBusy] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  const [workflowGraph, setWorkflowGraph] = useState<StudioGraph | null>(null);
  const [workflowTouched, setWorkflowTouched] = useState(false);
  const requestSerial = useRef(0);
  const listRequestSerial = useRef(0);

  const canWrite = Boolean(me?.permissions.includes('studio:project:write'));
  const formWritable = canWrite && loadState !== 'saving';
  const dirty = Boolean(editor && JSON.stringify(editor) !== savedSnapshot);
  const filteredDrafts = useMemo(() => drafts.filter(draft => draft.name.toLowerCase()
    .includes(search.toLowerCase())), [drafts, search]);
  const validationErrors = editor ? validateEditor(editor) : [];
  const previewFresh = Boolean(preview && editor && preview.input === JSON.stringify(editor.manifest));
  const previewFile = preview?.result.files.find(file => file.path === previewPath);

  useEffect(() => {
    if (!dirty && !workflowTouched) return;
    function warn(event: BeforeUnloadEvent) { event.preventDefault(); }
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, workflowTouched]);

  function confirmLeave(): boolean {
    return (!dirty && !workflowTouched) || window.confirm('当前项目表单或工作流画板有未保存修改。确定离开并丢弃本地修改吗？');
  }

  function resetWorkspace() {
    requestSerial.current += 1;
    listRequestSerial.current += 1;
    setCredentials(null); setMe(null); setDrafts([]); setNextDraftCursor(null); setLoadingMore(false); setSelectedId(null);
    setRevision(null); setEditor(null); setUnsupported(null); setSavedSnapshot('');
    setPassword(''); setError(''); setNotice(''); setAttempted(false); setLoadState('idle');
    setPage('model'); setPreview(null); setPreviewPath(''); setWorkflowGraph(null); setWorkflowTouched(false);
  }

  async function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setNotice('');
    if (!/^[a-z][a-z0-9-]{2,62}\/[a-z][a-z0-9_.-]{2,62}$/.test(login.trim()) || !password) {
      setError('请输入 tenant/username 格式的登录名和密码。'); return;
    }
    setLoadState('connecting');
    const serial = ++listRequestSerial.current;
    const candidate = { login: login.trim(), password };
    try {
      const api = platformApi(candidate);
      const identity = await api.me();
      if (!identity.permissions.includes('studio:project:read')) {
        throw new Error('当前账户缺少 studio:project:read，不能进入项目草稿管理。');
      }
      const firstPage = await api.listDraftPage();
      if (serial !== listRequestSerial.current) return;
      setCredentials(candidate); setMe(identity); setDrafts(firstPage.items);
      setNextDraftCursor(firstPage.nextCursor); setPassword('');
      setNotice(`已连接租户 ${identity.tenantSlug}，已加载 ${firstPage.items.length} 个项目草稿。`);
    } catch (cause) { if (serial === listRequestSerial.current) setError(errorText(cause)); }
    finally { if (serial === listRequestSerial.current) setLoadState('idle'); }
  }

  async function refresh() {
    if (!credentials) return;
    const serial = ++listRequestSerial.current;
    setLoadState('loading'); setLoadingMore(false); setError('');
    try {
      const firstPage = await platformApi(credentials).listDraftPage();
      if (serial !== listRequestSerial.current) return;
      setDrafts(firstPage.items); setNextDraftCursor(firstPage.nextCursor);
      setNotice('项目草稿列表已从服务端刷新。');
    } catch (cause) { if (serial === listRequestSerial.current) setError(errorText(cause)); }
    finally { if (serial === listRequestSerial.current) setLoadState('idle'); }
  }

  async function loadMoreDrafts() {
    if (!credentials || !nextDraftCursor || loadingMore || loadState !== 'idle') return;
    const serial = ++listRequestSerial.current;
    setLoadingMore(true); setError('');
    try {
      const nextPage = await platformApi(credentials).listDraftPage(30, nextDraftCursor);
      if (serial !== listRequestSerial.current) return;
      setDrafts(current => {
        const seen = new Set(current.map(draft => draft.id));
        return [...current, ...nextPage.items.filter(draft => !seen.has(draft.id))];
      });
      setNextDraftCursor(nextPage.nextCursor);
    } catch (cause) { if (serial === listRequestSerial.current) setError(errorText(cause)); }
    finally { if (serial === listRequestSerial.current) setLoadingMore(false); }
  }

  async function openDraft(id: string, skipConfirm = false) {
    if (!credentials || (!skipConfirm && !confirmLeave())) return;
    const serial = ++requestSerial.current;
    setLoadState('loading'); setError(''); setNotice(''); setAttempted(false);
    try {
      const draft = await platformApi(credentials).getDraft(id);
      if (serial !== requestSerial.current) return;
      setSelectedId(id); setRevision(draft.revision); setPage('model');
      setPreview(null); setPreviewPath(''); setWorkflowGraph(null); setWorkflowTouched(false);
      if (isEditableManifest(draft.manifest)) {
        const value = { name: draft.name, manifest: structuredClone(draft.manifest) };
        setEditor(value); setSavedSnapshot(JSON.stringify(value)); setUnsupported(null);
      } else {
        setEditor(null); setSavedSnapshot(''); setUnsupported(draft);
        setError('此草稿的 Manifest 不符合当前 v0.3 生成器契约；已保留原文，只能查看，不能用此表单覆盖。');
      }
    } catch (cause) { if (serial === requestSerial.current) setError(errorText(cause)); }
    finally { if (serial === requestSerial.current) setLoadState('idle'); }
  }

  function newDraft() {
    if (!canWrite || !confirmLeave()) return;
    requestSerial.current += 1;
    const value = blankEditor();
    setSelectedId(null); setRevision(null); setEditor(value); setUnsupported(null);
    setSavedSnapshot(JSON.stringify(value)); setAttempted(false); setError(''); setNotice('');
    setPage('model'); setPreview(null); setPreviewPath(''); setWorkflowGraph(null); setWorkflowTouched(false);
  }

  function goWorkflow() {
    if (!workflowGraph) {
      const artifact = editor?.manifest.project.artifactId;
      const id = artifact ? `app.${artifact.replaceAll('-', '_')}.default_qa` : undefined;
      setWorkflowGraph(starterGraph(id));
    }
    setPage('workflow');
  }

  function updateEditor(change: (value: EditorValue) => void) {
    if (loadState === 'saving') return;
    setEditor(current => {
      if (!current) return current;
      const next = mutableManifest(current);
      change(next);
      return next;
    });
    setNotice('');
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!credentials || !editor || !canWrite) return;
    setAttempted(true); setError(''); setNotice('');
    const issues = validateEditor(editor);
    if (issues.length > 0) {
      setError(`配置有 ${issues.length} 项校验问题，请查看表单底部的校验清单。`);
      document.getElementById('validation-result')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    setLoadState('saving');
    try {
      const api = platformApi(credentials);
      const saved = selectedId && revision !== null
        ? await api.updateDraft(selectedId, editor, revision)
        : await api.createDraft(editor);
      if (!isEditableManifest(saved.manifest)) throw new Error('服务端已保存，但返回的 Manifest 不符合当前编辑器契约。请重新加载草稿核对。');
      const value = { name: saved.name, manifest: structuredClone(saved.manifest) };
      setEditor(value); setSavedSnapshot(JSON.stringify(value));
      setSelectedId(saved.id); setRevision(saved.revision); setAttempted(false);
      setNotice(`服务端已保存草稿 · 修订 ${saved.revision}。尚未生成源码或发布工作流。`);
      try {
        const serial = ++listRequestSerial.current;
        setLoadingMore(false);
        const firstPage = await api.listDraftPage();
        if (serial === listRequestSerial.current) {
          setDrafts(firstPage.items); setNextDraftCursor(firstPage.nextCursor);
        }
      }
      catch (cause) { setError(`草稿已保存，但刷新列表失败：${errorText(cause)}`); }
    } catch (cause) { setError(errorText(cause)); }
    finally { setLoadState('idle'); }
  }

  async function previewCode() {
    if (!credentials || !editor || !canWrite) return;
    setAttempted(true); setError(''); setNotice('');
    const issues = validateEditor(editor);
    if (issues.length > 0) {
      setError(`配置有 ${issues.length} 项校验问题，无法请求真实源码预览。`);
      document.getElementById('validation-result')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const input = JSON.stringify(editor.manifest);
    const serial = requestSerial.current;
    setPreviewBusy(true);
    try {
      const result = await platformApi(credentials).previewCode(editor.manifest);
      if (result.previewSchemaVersion !== '1.0.0' || !Array.isArray(result.files) ||
          result.files.some(file => typeof file.path !== 'string' || typeof file.content !== 'string'
            || typeof file.sha256 !== 'string' || !Number.isInteger(file.bytes))) {
        throw new Error('服务端源码预览响应不符合 v1 契约。');
      }
      if (serial !== requestSerial.current) return;
      setPreview({ result, input });
      setPreviewPath(result.files[0]?.path ?? '');
      setNotice(`服务端已生成 ${result.files.length} 个文件的源码预览；尚未导出、构建或发布。`);
    } catch (cause) { if (serial === requestSerial.current) setError(errorText(cause)); }
    finally { setPreviewBusy(false); }
  }

  async function downloadSource() {
    if (!credentials || !editor || !canWrite || !previewFresh) return;
    setExportBusy(true); setError(''); setNotice('');
    try {
      const zip = await platformApi(credentials).exportCode(editor.manifest);
      if (zip.size === 0) throw new Error('服务端返回了空 ZIP，源码导出未完成。');
      const url = URL.createObjectURL(zip);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${editor.manifest.project.artifactId}-source-draft.zip`;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      setNotice(`已接收服务端源码草稿 ZIP（${zip.size.toLocaleString('zh-CN')} 字节）。本次配置尚未逐项编译、测试或部署。`);
    } catch (cause) { setError(errorText(cause)); }
    finally { setExportBusy(false); }
  }

  const headline = unsupported?.name || editor?.name || (editor ? '新建项目草稿' : '选择一个项目草稿');

  return <div className="app-shell">
    <aside className="sidebar" aria-label="平台导航">
      <div className="brand"><div className="brand-mark">Y</div><div><strong>YUAN</strong><span>SCAFFOLD / STUDIO</span></div></div>
      <div className="sidebar-section-label">工作空间</div>
      <button className={`nav-item ${page === 'model' ? 'active' : ''}`} onClick={() => setPage('model')}>
        <span className="nav-icon">▦</span><span>项目与数据模型</span><span className="nav-chevron">›</span>
      </button>
      <button className={`nav-item ${page === 'workflow' ? 'active' : ''}`} onClick={goWorkflow}>
        <span className="nav-icon">◇</span><span>Agent 工作流</span><span className="nav-chevron">›</span>
      </button>
      <div className="sidebar-section-label secondary-label">开发流程</div>
      <div className="nav-item muted-item"><span className="nav-icon">⌘</span><span>源码预览 / 导出</span><span className="nav-state">项目内</span></div>
      <div className="nav-item muted-item"><span className="nav-icon">◎</span><span>发布中心</span><span className="nav-state">待接入</span></div>
      <div className="sidebar-bottom"><span className="status-dot" />v0.4 开发纵切<span className="sidebar-bottom-sub">独立管理端 · 草稿管理</span></div>
    </aside>

    <div className="main-area">
      <header className="topbar"><div className="breadcrumb">开发平台 <span>/</span> {page === 'model' ? '项目与数据模型' : 'Agent 工作流'}</div>
        <div className="topbar-right"><span className="environment-tag">LOCAL DEVELOPMENT</span>
          {me && <><span className="identity-chip"><span className="avatar">{me.username.slice(0, 1).toUpperCase()}</span>{me.tenantSlug} / {me.username}</span>
            <button className="text-button" onClick={() => { if (confirmLeave()) resetWorkspace(); }}>断开连接</button></>}
        </div></header>

      <main className="workspace">
        <div className="page-heading"><div><div className="eyebrow">YUAN SCAFFOLD · BUILD YOUR APPLICATION</div>
          <h1>{page === 'model' ? '项目配置中心' : 'Agent 工作流'}</h1>
          <p>{page === 'model' ? '定义项目、实体字段与权限，保存可审查的生成器 Manifest。' : '为项目配置执行链路。画板的静态校验将由服务端负责。'}</p></div>
          <div className="heading-badge">v0.4 <span>●</span> DRAFT</div>
        </div>

        {!me ? <section className="login-card" aria-labelledby="login-title"><div className="login-illustration"><span>01</span><div className="mini-route"><i /><i /><i /></div><strong>项目建模<br />从可信草稿开始</strong><p>连接 Java Control Plane 后，读取和保存租户内的项目草稿。</p></div>
          <form className="login-form" onSubmit={connect}><div className="section-kicker">连接平台</div><h2 id="login-title">登录开发工作区</h2>
            <p>使用平台账号验证身份与 <code>studio:project:read</code> 权限。凭据仅保存在当前页面内存。</p>
            <label><Label>租户 / 用户名</Label><input autoComplete="username" value={login}
              onChange={event => setLogin(event.target.value)} placeholder="demo/admin" required /></label>
            <label><Label>密码</Label><input type="password" autoComplete="current-password" value={password}
              onChange={event => setPassword(event.target.value)} placeholder="输入平台密码" required /></label>
            {error && <div className="alert error-alert" role="alert">{error}</div>}
            <button className="primary-button full" type="submit" disabled={loadState !== 'idle'}>{loadState === 'connecting' ? '正在连接…' : '连接并读取草稿'}<span>→</span></button>
            <p className="login-footnote">开发验证阶段使用 HTTP Basic；正式浏览器登录与安全部署尚待验收。</p>
          </form></section> : <>
          {notice && <div className="alert success-alert" role="status"><span>✓</span>{notice}<button type="button" aria-label="关闭消息" onClick={() => setNotice('')}>×</button></div>}
          {error && <div className="alert error-alert" role="alert"><span>!</span>{error}<button type="button" aria-label="关闭错误" onClick={() => setError('')}>×</button></div>}

          <div className="stats-row"><div className="stat-card"><span>已加载项目草稿</span><strong>{drafts.length}</strong><small>当前租户 · {nextDraftCursor ? '可继续加载' : '已加载全部'}</small></div>
            <div className="stat-card"><span>当前工作区</span><strong className="stat-word">{me.tenantSlug}</strong><small>{canWrite ? '可编辑草稿' : '只读访问'}</small></div>
            <div className="stat-card"><span>生成器契约</span><strong className="stat-word">0.3.0</strong><small>单实体 Java / React</small></div></div>

          <div className={`work-grid ${page === 'workflow' ? 'workflow-wide' : ''}`}><section className="project-pane" aria-labelledby="projects-title">
            <div className="pane-header"><div><div className="section-kicker">WORKSPACE</div><h2 id="projects-title">项目草稿</h2></div>
              <button type="button" className="icon-button" onClick={refresh} disabled={loadState !== 'idle'} title="从服务端刷新" aria-label="刷新草稿">↻</button></div>
            <div className="search-wrap"><span>⌕</span><input aria-label="筛选项目草稿" value={search} onChange={event => setSearch(event.target.value)} placeholder="查找项目草稿" /></div>
            <button className="new-project" type="button" onClick={newDraft} disabled={!canWrite || loadState !== 'idle'}><span>＋</span> 新建项目草稿</button>
            <div className="project-list">
              {filteredDrafts.length === 0 && <div className="empty-list">{drafts.length === 0 ? '当前租户没有项目草稿。' : nextDraftCursor ? '已加载草稿中没有匹配项，可继续加载更多。' : '没有匹配的项目草稿。'}</div>}
              {filteredDrafts.map(draft => <button type="button" key={draft.id}
                className={`project-item ${selectedId === draft.id ? 'selected' : ''}`}
                onClick={() => openDraft(draft.id)} disabled={loadState === 'saving'}>
                <span className="project-symbol">{draft.name.slice(0, 1).toUpperCase()}</span><span className="project-info"><strong>{draft.name}</strong><small>修订 {draft.revision} · {dateOnly(draft.updatedAt)}</small></span><span className="project-arrow">›</span>
              </button>)}
            </div>
            {nextDraftCursor && <button type="button" className="draft-load-more" onClick={loadMoreDrafts}
              disabled={loadingMore || loadState !== 'idle'}>{loadingMore ? '加载中…' : '加载更多草稿'}</button>}
            <div className="pane-footer">已加载 {drafts.length} 项，按最近更新时间排序；搜索仅筛选已加载草稿。</div>
          </section>

          <section className="editor-pane" aria-labelledby="editor-title">
            <div className="editor-heading"><div><div className="section-kicker">{selectedId ? `PROJECT / ${selectedId.slice(0, 8)}` : 'PROJECT / NEW'}</div>
              <h2 id="editor-title">{page === 'workflow' ? '工作流画板' : headline}</h2>
              <p>{page === 'workflow' ? `项目上下文：${editor?.name || '通用样图'}。画板暂存于页面内存；静态有效不代表可执行或可发布。` : selectedId ? `服务端修订 ${revision ?? '—'} · ${dirty ? '有未保存修改' : '已同步'}` : '填写配置并保存为租户内项目草稿。'}</p></div>
              {page === 'model' && <span className={`sync-pill ${dirty ? 'unsaved' : ''}`}>{dirty ? '未保存' : selectedId ? '已同步' : '草稿'}</span>}</div>

            {page === 'workflow' && workflowGraph && credentials ? <WorkflowEditor
              credentials={credentials} canWrite={canWrite} graph={workflowGraph}
              onGraphChange={value => { setWorkflowGraph(value); setWorkflowTouched(true); }}
              onError={setError} onNotice={setNotice} />
              : unsupported ? <div className="unsupported-panel"><h3>Manifest 版本不兼容</h3><p>此草稿不会被当前表单重写。可查看原始内容，待匹配的编辑器版本上线后再修改。</p><pre>{JSON.stringify(unsupported.manifest, null, 2)}</pre></div>
              : editor ? <form className="editor-form" onSubmit={save} noValidate>
                <div className="form-section"><div className="form-section-head"><div className="section-number">01</div><div><h3>项目标识</h3><p>确定生成物的 Maven 坐标和 Java 包路径。</p></div></div>
                  <div className="form-grid"><label className="span-2"><Label hint="仅作为平台草稿显示名称">项目名称</Label>
                    <input value={editor.name} disabled={!formWritable} maxLength={120} placeholder="例如：资产管理应用"
                      onChange={event => updateEditor(value => { value.name = event.target.value; })} /></label>
                    <label><Label>Group ID</Label><input value={editor.manifest.project.groupId} disabled={!formWritable} placeholder="dev.yuan.app"
                      onChange={event => updateEditor(value => { value.manifest.project.groupId = event.target.value; })} /></label>
                    <label><Label>Artifact ID</Label><input value={editor.manifest.project.artifactId} disabled={!formWritable} placeholder="asset-app"
                      onChange={event => updateEditor(value => { value.manifest.project.artifactId = event.target.value; })} /></label>
                    <label className="span-2"><Label hint="须位于 Group ID 命名空间下">Java 包名</Label>
                      <input value={editor.manifest.project.packageName} disabled={!formWritable} placeholder="dev.yuan.app.asset"
                        onChange={event => updateEditor(value => { value.manifest.project.packageName = event.target.value; })} /></label></div></div>

                <div className="form-section"><div className="form-section-head"><div className="section-number">02</div><div><h3>业务实体</h3><p>当前生成器每个 Manifest 支持一个 CRUD 实体。</p></div></div>
                  <div className="form-grid"><label><Label hint="Java 类名，首字母大写">实体名称</Label>
                    <input value={editor.manifest.entity.name} disabled={!formWritable} placeholder="Asset"
                      onChange={event => updateEditor(value => { value.manifest.entity.name = event.target.value; })} /></label>
                    <label><Label hint="数据库表名，snake_case">数据表</Label>
                      <input value={editor.manifest.entity.table} disabled={!formWritable} placeholder="assets"
                        onChange={event => updateEditor(value => { value.manifest.entity.table = event.target.value; })} /></label>
                    <label className="span-2"><Label hint="以 /api/ 开头的资源路径">API 路径</Label>
                      <input value={editor.manifest.entity.apiPath} disabled={!formWritable} placeholder="/api/assets"
                        onChange={event => updateEditor(value => { value.manifest.entity.apiPath = event.target.value; })} /></label></div></div>

                <div className="form-section"><div className="form-section-head"><div className="section-number">03</div><div><h3>字段配置</h3><p>字段类型、必填与租户内唯一约束会进入生成代码和数据库迁移。</p></div></div>
                  <div className="field-list">{editor.manifest.entity.fields.map((field, index) => <FieldCard
                    key={index} field={field} index={index} canWrite={formWritable}
                    onChange={change => updateEditor(value => {
                      value.manifest.entity.fields[index] = { ...value.manifest.entity.fields[index], ...change };
                      for (const key of ['maxLength', 'minimum', 'unique'] as const) {
                        if (value.manifest.entity.fields[index][key] === undefined) delete value.manifest.entity.fields[index][key];
                      }
                    })}
                    onRemove={() => updateEditor(value => { value.manifest.entity.fields.splice(index, 1); })} />)}</div>
                  <button type="button" className="add-field" disabled={!formWritable || editor.manifest.entity.fields.length >= 32}
                    onClick={() => updateEditor(value => { value.manifest.entity.fields.push(blankField()); })}>＋ 添加字段 <span>{editor.manifest.entity.fields.length} / 32</span></button></div>

                <div className="form-section"><div className="form-section-head"><div className="section-number">04</div><div><h3>权限码</h3><p>生成应用中的读、写操作使用两个不同的权限码。</p></div></div>
                  <div className="form-grid"><label><Label>读取权限</Label>
                    <input value={editor.manifest.entity.permissions.read} disabled={!formWritable} placeholder="asset:read"
                      onChange={event => updateEditor(value => { value.manifest.entity.permissions.read = event.target.value; })} /></label>
                    <label><Label>写入权限</Label>
                      <input value={editor.manifest.entity.permissions.write} disabled={!formWritable} placeholder="asset:write"
                        onChange={event => updateEditor(value => { value.manifest.entity.permissions.write = event.target.value; })} /></label></div></div>

                {attempted && <div id="validation-result" className={`validation-panel ${validationErrors.length ? 'invalid' : 'valid'}`} role="status">
                  <strong>{validationErrors.length ? `生成器契约校验未通过 · ${validationErrors.length} 项` : '生成器契约校验通过'}</strong>
                  {validationErrors.length > 0 && <ul>{validationErrors.map((issue, index) => <li key={`${index}-${issue}`}>{issue}</li>)}</ul>}
                </div>}
                <div className="form-actions"><span>保存仅写入项目草稿；源码预览由服务端生成，不等于导出或发布。</span>
                  <div className="action-buttons"><button type="button" className="secondary-button" disabled={!canWrite || loadState !== 'idle' || previewBusy}
                    onClick={previewCode}>{previewBusy ? '预览生成中…' : '预览源码'}</button>
                    <button type="submit" className="primary-button" disabled={!canWrite || loadState !== 'idle' || previewBusy}>{loadState === 'saving' ? '保存中…' : selectedId ? '保存修订' : '创建草稿'}<span>→</span></button></div></div>
                {preview && <section className="code-preview" aria-label="服务端源码预览">
                  <div className="code-preview-head"><div><div className="section-kicker">SERVER GENERATED PREVIEW</div><h3>源码预览</h3>
                    <p>{preview.result.files.length} 个文件 · {preview.result.totalBytes.toLocaleString('zh-CN')} 字节 · 模板 {preview.result.generatorVersion}</p></div>
                    <div className="preview-head-actions"><span className={`sync-pill ${previewFresh ? '' : 'unsaved'}`}>{previewFresh ? '与当前配置一致' : '配置已变化'}</span>
                      <button type="button" className="secondary-button" disabled={!previewFresh || exportBusy || previewBusy || loadState !== 'idle'}
                        onClick={downloadSource}>{exportBusy ? '导出中…' : '下载源码草稿 ZIP'}</button></div></div>
                  {!previewFresh && <div className="preview-stale">当前表单已修改。下面的文件来自上一次预览，请重新请求服务端生成。</div>}
                  <div className="code-preview-body"><div className="code-file-list">{preview.result.files.map(file =>
                    <button key={file.path} type="button" className={file.path === previewPath ? 'selected' : ''}
                      onClick={() => setPreviewPath(file.path)} title={file.path}>{file.path}</button>)}</div>
                    <div className="code-file-content"><div className="code-file-meta"><strong>{previewFile?.path || '选择文件'}</strong>
                      {previewFile && <span>SHA-256 {previewFile.sha256.slice(0, 16)}… · {previewFile.bytes} B</span>}</div>
                      <pre>{previewFile?.content ?? ''}</pre></div></div>
                </section>}
              </form> : <div className="empty-editor"><div className="empty-editor-icon">▦</div><h3>开始配置项目</h3>
                <p>从左侧选择现有草稿，或新建一个项目。配置会在提交时通过生成器契约校验。</p>
                {canWrite && <button type="button" className="primary-button" onClick={newDraft}>新建项目草稿 <span>→</span></button>}</div>}
          </section></div>
        </>}
      </main>
    </div>
  </div>;
}
