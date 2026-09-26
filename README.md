# Yuan Scaffold

一个正在设计和实现的开源企业级 AI 智能应用开发脚手架，目标仓库为 [YuyanYYt/Yuan_Scaffold](https://github.com/YuyanYYt/Yuan_Scaffold)。

项目包含 Spring Boot 企业应用基座、AI Agent 快速搭建框架和低代码源码开发平台。以 **LangGraph** 作为 Agent 工作流运行核心，以 **Spring Boot** 承担身份、租户、权限、业务服务、发布与审计，以 **React + React Flow** 提供可视化工作流编辑器。平台将支持从模块选择、数据 / 表单 / 权限配置、工作流编排、RAG 评测与参数调优，到可维护 Java / Python / Web 源码导出的完整链路。企业后台与 CRUD 生成体验参考若依，项目模块和代码独立实现。

首个最终成品是 **HR 员工服务 AI 应用**。它用于证明脚手架可以生成并运行一套真实企业应用，覆盖政策问答、本人数据查询、请假申请、人工审批、RAG 质量治理、记忆、多 Agent 协作、审计和回滚。HR 业务不会写死到脚手架核心中。

## 当前阶段

- 已将两份项目开发地图与 Agent 组件需求归并为本仓库唯一的 [项目总地图](ProjectDocs/项目总地图.md)，采用 S0-S13 开发顺序，排除教学流程限制。
- 已完成 React Flow 与主流可视化 AI 项目中文本处理节点的源码调研。
- 原 LangGraph 项目中已经验证的 Agent Runtime 与 RAG 摄取能力只登记为迁移候选；尚未复制到本仓库，也不视为本项目已实现。
- MCP Scale、短期与三类长期记忆、多 Agent 可选配置、分段 TopK 和 HNSW 参数实验已进入开发地图；均未实现。
- 当前仓库暂不锁定具体依赖版本，待第一阶段建立可运行基线时统一确定。

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

详细边界、模块分类、RAG 六道质量门、代码生成链路、HR 成品验收、版本提交规则和阶段状态，以项目总地图为准。每个交付版本都需提交到本仓库，并保留对应验证证据。
