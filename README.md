# Yuan Scaffold

一个正在设计和实现的开源企业级 AI 智能应用开发脚手架，目标仓库为 [YuyanYYt/Yuan_Scaffold](https://github.com/YuyanYYt/Yuan_Scaffold)。

项目包含 Spring Boot 企业应用基座、AI Agent 快速搭建框架和低代码源码开发平台。脚手架本体自主实现系统管理、模块装配和代码生成，只参考若依的设计理念与交互；**本项目不是若依的 Fork 或二次开发**。React Flow 画板是平台中的 Agent 开发模块。以 **LangGraph** 作为 Agent 工作流运行核心，以 **Spring Boot** 承担身份、租户、权限、业务服务、发布与审计。平台将支持从模块选择、数据 / 表单 / 权限配置、工作流编排、RAG 评测与参数调优，到可维护 Java / Python / Web 源码导出的完整链路。

首个生成应用是 **HR 工作台**。它用于证明脚手架可以生成并运行一套真实企业应用，覆盖政策问答、本人数据查询、请假申请、人工审批、RAG 质量治理、记忆、多 Agent 协作、审计和回滚。HR 工作台只是第一次应用，不定义脚手架平台的页面形态或业务边界。

## 当前阶段

- 已将两份项目开发地图与 Agent 组件需求归并为本仓库唯一的 [项目总地图](ProjectDocs/项目总地图.md)，以主版本覆盖矩阵推进螺旋式迭代，并用 S0-S13 记录能力与阶段门。
- 已完成 React Flow 与主流可视化 AI 项目中文本处理节点的源码调研。
- [Workflow IR v0.1 契约包](packages/workflow-contracts/README.md) 已包含节点注册表、两个 HR 草稿样例和静态校验器；28 项测试通过。它尚不能发布或执行工作流。
- [Python LangGraph v0.2 运行包](services/agent-runtime-python/README.md) 已把六节点线性 IR 编译并执行，使用 SQLite Checkpoint 和脱敏节点 Trace；10 项合成适配器测试通过。真实授权、检索、模型和答案门尚未接入。
- 原 LangGraph 项目中已经验证的 Agent Runtime 与 RAG 摄取能力只登记为迁移候选；尚未复制到本仓库，也不视为本项目已实现。
- 短期记忆的基础 Checkpoint 已在 v0.2 受限运行包中实现；MCP Scale、完整短期记忆治理、三类长期记忆、多 Agent 可选配置、分段 TopK 和 HNSW 参数实验已进入开发地图，尚未实现。
- 契约包已固定 Node.js 最低版本与 Ajv；受限 Python 运行包已锁定 Python / LangGraph / SQLite Checkpoint 依赖。Java 与 React 的组合版本待对应闭环建立时确定。

## 文档入口

- [项目总地图](ProjectDocs/项目总地图.md)
- [React Flow 与 NLP 节点调研](ProjectDocs/ReactFlow与NLP节点调研.md)
- [项目记忆](ProjectDocs/PROJECT_MEMORY.md)

## 核心数据流

```text
React Flow Studio
  -> versioned Workflow IR
  -> validation / testing / evaluation
  -> LangGraph runtime or source-code export
  -> Java business services / tools / knowledge / memory
  -> governed answer, artifact and audit trail
```

详细边界、模块分类、RAG 六道质量门、代码生成链路、首个 HR 生成应用验收、版本提交规则和阶段状态，以项目总地图为准。每个交付版本都需提交到本仓库，并保留对应验证证据。
