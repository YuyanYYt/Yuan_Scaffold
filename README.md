# Yuan Scaffold

一个正在设计和实现的开源企业级 AI 智能应用开发脚手架，目标仓库为 [YuyanYYt/Yuan_Scaffold](https://github.com/YuyanYYt/Yuan_Scaffold)。

项目包含 Spring Boot 企业应用基座、AI Agent 快速搭建框架和低代码源码开发平台。脚手架本体自主实现系统管理、模块装配和代码生成，只参考若依的设计理念与交互；**本项目不是若依的 Fork 或二次开发**。React Flow 画板是平台中的 Agent 开发模块。以 **LangGraph** 作为 Agent 工作流运行核心，以 **Spring Boot** 承担身份、租户、权限、业务服务、发布与审计。平台将支持从模块选择、数据 / 表单 / 权限配置、工作流编排、RAG 评测与参数调优，到可维护 Java / Python / Web 源码导出的完整链路。

首个**完整业务验收应用**规划为 **HR 工作台**。它将证明脚手架能够生成并运行真实企业应用，覆盖政策问答、本人数据查询、请假申请、人工审批、RAG 质量治理、记忆、多 Agent 协作、审计和回滚。当前先用非 HR 的 [Asset 小样](examples/asset-app/README.md)验证通用生成器；HR 不定义脚手架平台的页面形态或业务边界。

## 当前阶段

- 已将两份项目开发地图与 Agent 组件需求归并为本仓库唯一的 [项目总地图](ProjectDocs/项目总地图.md)，以主版本覆盖矩阵推进螺旋式迭代，并用 S0-S13 记录能力与阶段门。
- 已完成 React Flow 与主流可视化 AI 项目中文本处理节点的源码调研。
- [Workflow IR v0.1 契约包](packages/workflow-contracts/README.md) 已包含节点注册表、两个 HR 草稿样例和静态校验器；28 项测试通过。它尚不能发布或执行工作流。
- [Python LangGraph v0.2 运行包](services/agent-runtime-python/README.md) 已把六节点线性 IR 编译并执行，使用 SQLite Checkpoint 和脱敏节点 Trace；10 项合成适配器测试通过。真实授权、检索、模型和答案门尚未接入。
- [v0.3 单实体生成器](packages/app-generator/README.md) 可从 Manifest 生成独立 Spring Boot 4.1.1 / Java 21 后端和 React 19.3 页面，保护人工改动；Asset 小样已完成后端测试、Web 构建、HTTP 与浏览器验收。它是非 HR 通用性样例，不是完整开发平台。
- [v0.3 Java 企业基座](services/platform-java/README.md) 已实现 Web API、认证、租户隔离、用户 / 角色 / 菜单创建与读取、CSRF、Flyway、事务审计和健康检查；12 项测试包含 Java→Python 签名协议验证。[Python 内部 API](services/agent-api-python/README.md) 已实现验签、状态、幂等和不可用时的明确拒绝，12 项测试通过。
- v0.3 的约定最小验收门已通过：Java 签名客户端与实际启动的本地 FastAPI 完成 3 项 HTTP 联测，缺少目录 / 执行器时明确拒绝；常规回归也已通过。联测成功路径只使用测试专用执行器。**目前没有真实 Agent 问答闭环，也没有 v0.3 正式发布标签。**
- 原 LangGraph 项目中已经验证的 Agent Runtime 与 RAG 摄取能力只登记为迁移候选；尚未复制到本仓库，也不视为本项目已实现。
- 短期记忆的基础 Checkpoint 已在 v0.2 受限运行包中实现；MCP Scale、完整短期记忆治理、三类长期记忆、多 Agent 可选配置、分段 TopK 和 HNSW 参数实验已进入开发地图，尚未实现。
- 当前已验证的主要组合包括 Node.js 24.18、Python 3.11、Java 21、Spring Boot 4.1.1、React 19.3 / Vite 8.3.1 和 H2；其他数据库与生产部署仍需单独验收。具体能力、版本和证据见总地图第 11.2、11.3 节。

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

详细边界、模块分类、RAG 六道质量门、代码生成链路、首个完整 HR 业务应用验收、版本提交规则和阶段状态，以项目总地图为准。每个交付版本都需提交到本仓库，并保留对应验证证据。
