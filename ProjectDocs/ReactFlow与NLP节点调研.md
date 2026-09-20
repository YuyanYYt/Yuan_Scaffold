# React Flow 与 NLP 节点调研

> 调研日期：2026-09-20  
> 目的：确认 React Flow 负责什么，主流可视化 AI 项目中的“文本 / NLP”节点实际执行什么，并约束本项目节点设计。

## 1. 直接结论

React Flow 自己不会理解自然语言，也不会自动识别意图、实体或语义。它是 React 里的流程图编辑与展示库，核心对象是节点、边、端口、位置、视口和交互状态。节点内部执行什么，由使用 React Flow 的应用自己实现。

主流 AI 工作流项目常见的“文本处理”大致分为三类：

1. **确定性预处理**：按字符、分隔符、Token、Markdown 标题等规则切块；它不理解业务语义。
2. **传统或本地 NLP**：分词、语言检测、NER、分类等，需要明确的算法或模型。
3. **LLM / Embedding 语义处理**：语义切分、意图判断、Query Rewrite、摘要、Rerank 等，需要调用模型。

因此，“NLP 节点会不会自己识别”这个问题的答案是：**不会由 React Flow 自己识别**。要看该节点绑定的后端实现。只配置 `chunkSize`、`chunkOverlap` 和 `separators` 的节点，本质上通常只是切块。

## 2. React Flow 本身

React Flow 官方示例把流程定义为 `nodes`、`edges` 和 `viewport`。Node 的 `data` 用于保存自定义数据，执行语义由应用提供。

对本项目的含义：

- React Flow 负责画布、拖拽、连线、节点配置和调试结果展示。
- 前端把 UI Graph 转换为版本化 Workflow IR。
- 后端验证 IR，再由 LangGraph 执行。
- 任何身份、权限、模型、RAG、Tool 或业务执行都不能只靠前端节点代码完成。

来源：

- [React Flow: Building a Flow](https://reactflow.dev/learn/concepts/building-a-flow)
- [React Flow: Terms and Definitions](https://reactflow.dev/learn/concepts/terms-and-definitions)

## 3. Flowise 源码观察

### 3.1 Recursive Character Text Splitter

源码位置：

- [`RecursiveCharacterTextSplitter.ts`](https://github.com/FlowiseAI/Flowise/blob/main/packages/components/nodes/textsplitters/RecursiveCharacterTextSplitter/RecursiveCharacterTextSplitter.ts)

该节点做了三件事：

1. 从节点配置读取 `chunkSize`、`chunkOverlap` 和可选 `separators`。
2. 把字符串参数转换成数字或数组。
3. 创建 LangChain 的 `RecursiveCharacterTextSplitter` 并返回。

核心行为可以概括为：

```ts
const splitter = new RecursiveCharacterTextSplitter(config)
```

它默认按双换行、换行、空格等层级寻找切点。这里没有实体识别、意图理解或业务语义判断。

### 3.2 Token Text Splitter

源码位置：

- [`TokenTextSplitter.ts`](https://github.com/FlowiseAI/Flowise/blob/main/packages/components/nodes/textsplitters/TokenTextSplitter/TokenTextSplitter.ts)

该节点选择 tokenizer 编码，把文本变成 BPE Token，按照 Token 数量和重叠窗口分组，再解码为文本块。它理解的是 tokenizer 的编码边界，不是文档的业务含义。

## 4. Dify 源码观察

源码位置：

- [`text_splitter.py`](https://github.com/langgenius/dify/blob/main/api/core/rag/splitter/text_splitter.py)
- [Dify React Flow 工作流入口](https://github.com/langgenius/dify/blob/main/web/app/components/workflow/index.tsx)

Dify 的前端用 React Flow 管理工作流画布，文本切分逻辑位于 Python 后端。其实现包括：

- 使用正则和分隔符拆分文本；
- 根据 `chunk_size` 与 `chunk_overlap` 合并小段；
- Token Splitter 使用 tokenizer 编码、滑动窗口和解码；
- Recursive Splitter 依次尝试段落、换行、空格和字符边界。

这说明画布节点只是配置和引用执行器。基础切分仍然是确定性算法。需要模型理解的环节应当作为另一个节点或 Provider 调用出现。

## 5. Langflow 源码观察

来源：

- [Langflow 仓库结构与组件开发说明](https://github.com/langflow-ai/langflow/blob/main/AGENTS.md)
- [`LCTextSplitterComponent`](https://github.com/langflow-ai/langflow/blob/main/src/lfx/src/lfx/base/textsplitters/model.py)
- [LFX README](https://github.com/langflow-ai/langflow/blob/main/src/lfx/README.md)

Langflow 前端使用 `@xyflow/react` 做图形界面，组件执行位于 Python 后端。文本切分组件的基类最终构建 `langchain_text_splitters.TextSplitter`。流程保存为图数据，再由后端图执行器运行。

这进一步确认：可视化框架与 NLP / Agent Runtime 是两层。前者描述和展示流程，后者执行组件。

## 6. 本项目的节点设计决策

### 6.1 不使用含义模糊的“NLP”总节点

节点面板改为明确分类：

| 类别 | 示例 | 执行机制 |
| --- | --- | --- |
| Text Processing | Normalize、Regex Extract、Recursive Split、Token Split | 纯规则或 tokenizer |
| NLP Model | Language Detect、NER、Intent Classifier | 明确的本地或远程 NLP 模型 |
| LLM Operation | Query Rewrite、Summarize、Judge、Structured Extract | 生成式模型 |
| RAG | Parse、Quality Gate、Embed、Retrieve、Rerank、Citation | RAG 组件与模型组合 |
| Agent | Router、ReAct、Planner、Evaluator、SubAgent | LangGraph 节点 / 子图 |

### 6.2 每个节点必须公开执行属性

节点注册信息至少包含：

- 执行器：Java、Python、本地 Worker 或外部服务；
- 是否调用模型及 Provider 类型；
- 输入、输出和状态 Schema；
- 确定性等级与是否可缓存；
- Token、费用、超时和重试预算；
- 所需 Capability、数据分级和网络权限；
- 错误语义与 fallback；
- 节点实现版本。

### 6.3 前后端数据边界

```text
React Flow Node
  -> NodeConfig
  -> Workflow IR Node
  -> server-side validation
  -> Node Registry resolves executor
  -> LangGraph invokes executor
  -> structured NodeResult + Trace
  -> React Flow displays status
```

前端不直接持有模型密钥，不签发租户 / 用户身份，不决定 Tool 授权，也不把未经校验的节点代码发送到生产运行时执行。

## 7. 对 RAG 切分功能的具体判断

基础切分节点可以实现为：

- 字符长度切分；
- 递归字符切分；
- Token 切分；
- Markdown / HTML / 代码结构切分；
- 父子块与 Sentence Window；
- 表格、页面和标题结构切分。

这些方法多数不需要 LLM。它们可以尽量保留结构，但“保留结构”不等于真正理解业务语义。

以下能力才属于模型参与的语义处理：

- 语义边界检测；
- 文档类型与意图分类；
- 实体和关系抽取；
- Query Rewrite / Decomposition；
- Contextual Chunking；
- LLM Rerank 或 Judge。

模型型节点不能替代确定性质量门。模型输出仍需 Schema、Evidence、置信度、预算、失败隔离和可回放 Trace。

## 8. 最终选择

本项目采用以下组合：

- React Flow：工作流设计与可视化；
- Workflow IR：跨语言、可版本化的唯一图契约；
- LangGraph：Agent 和工作流执行核心；
- Java：身份、权限、业务事务、控制面、发布和审计；
- Python：模型、RAG、Memory、评测和 Agent Runtime；
- Code Generator：从 Manifest + Workflow IR 导出可维护源码。

这套边界既能复用主流项目成熟的画布模式，又能避免把前端节点、文本切块和真正的自然语言理解混为一谈。
