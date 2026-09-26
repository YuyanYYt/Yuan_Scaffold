import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Background, Controls, Handle, MiniMap, Position, ReactFlow,
  applyEdgeChanges, applyNodeChanges
} from '@xyflow/react';
import type { Connection, Edge, EdgeChange, Node, NodeChange, NodeProps } from '@xyflow/react';
import { platformApi } from './api';
import starter from './workflow-starter.json';
import type { Credentials, StudioEdge, StudioGraph, StudioNode, StudioPort, WorkflowValidation } from './types';

const nodeLabels: Record<string, string> = {
  chat_input: '对话输入', auth_context: '可信身份', rag_retrieve: 'RAG 检索',
  answer_with_citation: '带引用回答', answer_gate: '答案门', chat_output: '对话输出'
};
const palette = Object.entries(nodeLabels);

type FlowData = Record<string, unknown> & {
  kind: string;
  label: string;
  input_ports: StudioPort[];
  output_ports: StudioPort[];
};
type FlowNode = Node<FlowData, 'studio'>;

function StudioNodeCard({ data, selected }: NodeProps<FlowNode>) {
  return <div className={`canvas-node ${selected ? 'canvas-node-selected' : ''}`}>
    {data.input_ports.map((port, index) => <Handle key={`in-${port.name}`} type="target" position={Position.Left}
      id={port.name} title={`${port.name} · ${port.schema_ref}`} style={{ top: `${(index + 1) * 100 / (data.input_ports.length + 1)}%` }} />)}
    <div className="canvas-node-kind">{data.kind.replaceAll('_', ' ').toUpperCase()}</div>
    <strong>{data.label}</strong>
    <small>{data.input_ports.length} 输入 · {data.output_ports.length} 输出</small>
    {data.output_ports.map((port, index) => <Handle key={`out-${port.name}`} type="source" position={Position.Right}
      id={port.name} title={`${port.name} · ${port.schema_ref}`} style={{ top: `${(index + 1) * 100 / (data.output_ports.length + 1)}%` }} />)}
  </div>;
}

const nodeTypes = { studio: StudioNodeCard };

function asFlowNode(node: StudioNode, selectedId: string | null): FlowNode {
  return { id: node.id, type: 'studio', position: node.position, selected: node.id === selectedId,
    data: { ...node.data, kind: node.type, label: node.data.label || nodeLabels[node.type] || node.type } };
}

function asFlowEdge(edge: StudioEdge): Edge {
  return { id: edge.id, source: edge.source, target: edge.target,
    sourceHandle: edge.sourceHandle, targetHandle: edge.targetHandle, type: 'smoothstep', animated: false };
}

function message(cause: unknown): string {
  if (cause instanceof TypeError) return '无法连接服务端静态校验 API，请检查 Java 服务与代理配置。';
  return cause instanceof Error ? cause.message : String(cause);
}

export function starterGraph(workflowId?: string): StudioGraph {
  const graph = structuredClone(starter) as StudioGraph;
  if (workflowId && /^[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*$/.test(workflowId)) {
    graph.workflow_id = workflowId;
  }
  return graph;
}

function Numeric({ label, value, min, onChange }: { label: string; value: number; min: number; onChange: (value: number) => void }) {
  return <label className="inspector-field"><span>{label}</span><input type="number" min={min} value={value}
    onChange={event => onChange(Number(event.target.value))} /></label>;
}

export default function WorkflowEditor({ credentials, canWrite, graph, onGraphChange, onError, onNotice }: {
  credentials: Credentials;
  canWrite: boolean;
  graph: StudioGraph;
  onGraphChange: (next: StudioGraph) => void;
  onError: (value: string) => void;
  onNotice: (value: string) => void;
}) {
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [configText, setConfigText] = useState('');
  const [configError, setConfigError] = useState('');
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<{ input: string; result: WorkflowValidation } | null>(null);
  const [showIr, setShowIr] = useState(false);
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const [compactCanvas, setCompactCanvas] = useState(() => window.innerWidth < 760);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width;
      if (width !== undefined) setCompactCanvas(width < 700);
    });
    observer.observe(canvas);
    return () => observer.disconnect();
  }, []);

  const nodes = useMemo(() => graph.nodes.map(node => asFlowNode(node, selectedNode)), [graph.nodes, selectedNode]);
  const edges = useMemo(() => graph.edges.map(asFlowEdge), [graph.edges]);
  const node = graph.nodes.find(item => item.id === selectedNode);
  const validationFresh = Boolean(validation && validation.input === JSON.stringify(graph));
  const validDraft = Boolean(validationFresh && validation?.result.valid && validation.result.ir?.status === 'DRAFT');

  function change(mutator: (next: StudioGraph) => void) {
    const next = structuredClone(graph);
    mutator(next);
    onGraphChange(next);
  }

  function onNodesChange(changes: NodeChange<FlowNode>[]) {
    const semantic = changes.filter(change => change.type === 'position' || change.type === 'remove');
    if (semantic.length === 0) return;
    const updated = applyNodeChanges(semantic, nodes);
    change(next => {
      const retained = new Set(updated.map(item => item.id));
      next.nodes = next.nodes.filter(item => retained.has(item.id));
      next.edges = next.edges.filter(item => retained.has(item.source) && retained.has(item.target));
      for (const item of updated) {
        const target = next.nodes.find(candidate => candidate.id === item.id);
        if (target) target.position = item.position;
      }
    });
    if (selectedNode && !updated.some(item => item.id === selectedNode)) setSelectedNode(null);
  }

  function onEdgesChange(changes: EdgeChange[]) {
    const semantic = changes.filter(change => change.type !== 'select');
    if (semantic.length === 0) return;
    const updated = applyEdgeChanges(semantic, edges);
    change(next => { next.edges = next.edges.filter(item => updated.some(edge => edge.id === item.id)); });
  }

  function onConnect(connection: Connection) {
    if (!canWrite || !connection.sourceHandle || !connection.targetHandle) return;
    const exists = graph.edges.some(edge => edge.source === connection.source && edge.target === connection.target
      && edge.sourceHandle === connection.sourceHandle && edge.targetHandle === connection.targetHandle);
    if (exists) return;
    change(next => next.edges.push({ id: `e_${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
      source: connection.source, target: connection.target,
      sourceHandle: connection.sourceHandle!, targetHandle: connection.targetHandle! }));
  }

  function addNode(type: string) {
    const template = (starter as StudioGraph).nodes.find(item => item.type === type);
    if (!template) return;
    if (type === 'chat_input' && graph.nodes.some(item => item.type === type)) {
      onError('当前受限契约只允许一个对话输入节点。'); return;
    }
    const id = `${type}_${crypto.randomUUID().replaceAll('-', '').slice(0, 8)}`;
    change(next => next.nodes.push({ id, type, position: { x: 120 + next.nodes.length * 38, y: 220 },
      data: structuredClone(template.data) }));
    selectNode(id, { ...template, id });
  }

  function selectNode(id: string | null, selected?: StudioNode) {
    setSelectedNode(id);
    setConfigError('');
    const value = selected ?? graph.nodes.find(item => item.id === id);
    setConfigText(value ? JSON.stringify(value.data.config, null, 2) : '');
  }

  function editNode(mutator: (value: StudioNode) => void) {
    if (!node) return;
    change(next => {
      const target = next.nodes.find(item => item.id === node.id);
      if (target) mutator(target);
    });
  }

  function editPort(side: 'input_ports' | 'output_ports', index: number, patch: Partial<StudioPort>) {
    editNode(value => { value.data[side][index] = { ...value.data[side][index], ...patch }; });
  }

  function applyConfig() {
    if (!node) return;
    try {
      const parsed: unknown = JSON.parse(configText);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('节点配置必须是 JSON 对象。');
      editNode(value => { value.data.config = parsed as Record<string, unknown>; });
      setConfigError('');
    } catch (cause) { setConfigError(message(cause)); }
  }

  async function validate() {
    if (!canWrite) return;
    setValidating(true); onError(''); onNotice('');
    const input = JSON.stringify(graph);
    try {
      const result = await platformApi(credentials).validateWorkflow(graph);
      if (result.scope !== 'draft_static_only' || result.publishable !== false || result.executable !== false
          || !Array.isArray(result.errors) || (result.valid && result.ir?.status !== 'DRAFT')) {
        throw new Error('服务端返回的工作流校验结果不符合受限 DRAFT 契约。');
      }
      setValidation({ input, result });
      if (result.valid) onNotice('服务端静态校验通过，已得到 DRAFT IR。该结果不可执行、不可发布，也未绑定真实资源。');
      else onError(`服务端静态校验未通过：${result.errors.length} 项问题。请查看画板下方错误列表。`);
    } catch (cause) { onError(message(cause)); }
    finally { setValidating(false); }
  }

  function downloadIr() {
    if (!validDraft || !validation?.result.ir) return;
    const blob = new Blob([`${JSON.stringify(validation.result.ir, null, 2)}\n`], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${graph.workflow_id.replaceAll('.', '-')}-draft-ir.json`;
    document.body.append(link);
    link.click(); link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  const bindings = graph.bindings as Record<string, Array<Record<string, unknown>>>;
  const resourceGroups = [
    ['models', '模型'], ['prompts', 'Prompt'], ['knowledge_bases', '知识库'], ['indexes', 'ANN 索引']
  ] as const;

  return <div className="workflow-editor">
    <div className="workflow-note"><strong>受限画板</strong><span>拖动节点、从端口连线、选中后修改属性。验证仅调用服务端静态转换；当前图保存在页面内存，未写入项目草稿。</span></div>
    <div className="workflow-controls-row"><label>Workflow ID<input disabled={!canWrite} value={graph.workflow_id}
      onChange={event => change(next => { next.workflow_id = event.target.value; })} /></label>
      <label>版本<input disabled={!canWrite} value={graph.version}
        onChange={event => change(next => { next.version = event.target.value; })} /></label>
      <button type="button" className="primary-button" disabled={!canWrite || validating} onClick={validate}>
        {validating ? '校验中…' : '服务端校验 DRAFT IR'} <span>→</span></button></div>
    <div className="workflow-layout"><div className="workflow-main"><div className="node-palette"><span>添加节点</span>
      {palette.map(([type, label]) => <button type="button" key={type} disabled={!canWrite} onClick={() => addNode(type)}
        title={type}>＋ {label}</button>)}</div>
      <div className="flow-canvas" ref={canvasRef} aria-label="Agent 工作流画板"><ReactFlow
        key={compactCanvas ? 'partial-view' : 'fit-view'}
        nodes={nodes} edges={edges} nodeTypes={nodeTypes} onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange} onConnect={onConnect} nodesDraggable={canWrite}
        nodesConnectable={canWrite} elementsSelectable={true} deleteKeyCode={canWrite ? ['Backspace', 'Delete'] : null}
        onNodeClick={(_, item) => selectNode(item.id)} onPaneClick={() => selectNode(null)}
        fitView={!compactCanvas} fitViewOptions={{ padding: .2 }}
        defaultViewport={compactCanvas ? { x: 34, y: 105, zoom: .9 } : undefined}
        minZoom={.3} maxZoom={1.5}>
        <Background gap={19} size={1} color="#dbe3f2" /><MiniMap pannable zoomable /><Controls /></ReactFlow></div>
      <div className="flow-caption">{graph.nodes.length} 节点 · {graph.edges.length} 连线 · {compactCanvas
        ? '窄屏显示链路局部：拖动画布查看其余节点，MiniMap 可快速定位'
        : '拖动端口建立连接 · 删除键移除选中元素'}</div></div>
      <aside className="workflow-inspector"><div className="inspector-head"><span>属性面板</span><strong>{node ? node.data.label || nodeLabels[node.type] || node.type : '选择节点'}</strong></div>
        {node ? <div className="inspector-body"><label className="inspector-field"><span>节点 ID</span><input readOnly value={node.id} /></label>
          <label className="inspector-field"><span>显示名称</span><input disabled={!canWrite} value={node.data.label ?? ''} placeholder={nodeLabels[node.type]}
            onChange={event => editNode(value => { value.data.label = event.target.value; })} /></label>
          <div className="inspector-subhead">输入端口</div>
          {node.data.input_ports.map((port, index) => <div className="port-editor" key={`in-${index}`}>
            <input aria-label={`输入端口 ${index + 1} 名称`} disabled={!canWrite} value={port.name}
              onChange={event => editPort('input_ports', index, { name: event.target.value })} />
            <input aria-label={`输入端口 ${index + 1} 类型`} disabled={!canWrite} value={port.schema_ref}
              onChange={event => editPort('input_ports', index, { schema_ref: event.target.value })} />
            <label><input type="checkbox" disabled={!canWrite} checked={port.required}
              onChange={event => editPort('input_ports', index, { required: event.target.checked })} />必需</label></div>)}
          {node.data.input_ports.length === 0 && <p className="inspector-empty">无输入端口</p>}
          <div className="inspector-subhead">输出端口</div>
          {node.data.output_ports.map((port, index) => <div className="port-editor" key={`out-${index}`}>
            <input aria-label={`输出端口 ${index + 1} 名称`} disabled={!canWrite} value={port.name}
              onChange={event => editPort('output_ports', index, { name: event.target.value })} />
            <input aria-label={`输出端口 ${index + 1} 类型`} disabled={!canWrite} value={port.schema_ref}
              onChange={event => editPort('output_ports', index, { schema_ref: event.target.value })} />
            <label><input type="checkbox" disabled={!canWrite} checked={port.required}
              onChange={event => editPort('output_ports', index, { required: event.target.checked })} />必需</label></div>)}
          {node.data.output_ports.length === 0 && <p className="inspector-empty">无输出端口</p>}
          {node.type === 'rag_retrieve' && <div className="rag-settings"><div className="inspector-subhead">RAG 查询参数</div>
            {(['dense_top_k', 'sparse_top_k', 'fusion_top_k', 'rerank_top_k', 'context_top_k', 'ef_search'] as const).map(key => {
              const retrieval = node.data.config.retrieval as Record<string, number>;
              return <Numeric key={key} label={key} min={1} value={retrieval?.[key] ?? 0}
                onChange={value => editNode(item => {
                  const current = item.data.config.retrieval as Record<string, unknown>;
                  item.data.config.retrieval = { ...current, [key]: value };
                  setConfigText(JSON.stringify(item.data.config, null, 2));
                })} />;
            })}</div>}
          <div className="inspector-subhead">节点配置 JSON</div>
          <textarea className="config-textarea" aria-label="节点配置 JSON" spellCheck={false} disabled={!canWrite}
            value={configText} onChange={event => setConfigText(event.target.value)} />
          {configError && <p className="config-error" role="alert">{configError}</p>}
          <button type="button" className="secondary-button apply-config" disabled={!canWrite}
            onClick={applyConfig}>应用节点配置</button></div>
          : <div className="inspector-empty larger">点击画布上的节点，查看并编辑其配置、输入端口与输出端口。</div>}
      </aside></div>
    <div className="binding-section"><div className="form-section-head"><div className="section-number">05</div><div><h3>资源声明</h3>
      <p>下方只是 DRAFT IR 中的资源标识。当前没有可信 Release Catalog，静态校验不会验证资源是否存在或授权。</p></div></div>
      <div className="binding-grid">{resourceGroups.map(([group, title]) => <label key={group}><span>{title} Resource ID</span>
        <input disabled={!canWrite} value={String(bindings[group]?.[0]?.resource_id ?? '')}
          onChange={event => change(next => {
            const items = next.bindings[group] as Array<Record<string, unknown>>;
            if (items?.[0]) items[0].resource_id = event.target.value;
          })} /></label>)}</div>
      <div className="binding-grid compact"><label><span>HNSW M</span><input type="number" min="2" disabled={!canWrite}
        value={Number((bindings.indexes?.[0]?.build_params as Record<string, unknown>)?.m ?? 0)}
        onChange={event => change(next => {
          const item = (next.bindings.indexes as Array<Record<string, unknown>>)[0];
          (item.build_params as Record<string, unknown>).m = Number(event.target.value);
        })} /></label>
        <label><span>HNSW efConstruction</span><input type="number" min="2" disabled={!canWrite}
          value={Number((bindings.indexes?.[0]?.build_params as Record<string, unknown>)?.ef_construction ?? 0)}
          onChange={event => change(next => {
            const item = (next.bindings.indexes as Array<Record<string, unknown>>)[0];
            (item.build_params as Record<string, unknown>).ef_construction = Number(event.target.value);
          })} /></label></div></div>
    {validation && <div className={`workflow-validation ${validation.result.valid && validationFresh ? 'passed' : 'failed'}`}>
      <div className="validation-top"><div><strong>{validation.result.valid ? '静态校验通过' : '静态校验未通过'}</strong>
        <p>{validationFresh ? '来自 Java 控制面 / studio-graph 的校验结果' : '画板已修改；下面是旧结果，请重新校验。'}</p></div>
        <span>DRAFT STATIC ONLY</span></div>
      <div className="validation-flags"><span>不可执行</span><span>不可发布</span><span>未绑定真实资源</span></div>
      {validation.result.errors.length > 0 && <ul className="workflow-errors">{validation.result.errors.map((issue, index) =>
        <li key={`${index}-${issue.code}`}><code>{issue.code}</code><span>{issue.path}</span>{issue.message}</li>)}</ul>}
      {validDraft && <div className="ir-actions"><button type="button" className="secondary-button" onClick={() => setShowIr(!showIr)}>
        {showIr ? '收起 DRAFT IR' : '查看服务端 DRAFT IR'}</button><button type="button" className="secondary-button" onClick={downloadIr}>下载 DRAFT IR JSON</button></div>}
      {validDraft && showIr && <pre className="ir-json">{JSON.stringify(validation.result.ir, null, 2)}</pre>}
    </div>}
  </div>;
}
