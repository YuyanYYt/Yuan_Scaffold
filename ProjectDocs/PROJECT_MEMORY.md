# 项目记忆

> 最后核对：2026-09-26
> 作用域：仅 `Yuan_Scaffold` 仓库（当前本地目录名为 `java-agent-scaffold`）。

## 稳定目标

- 构建开源企业级 Java + AI Agent 智能应用开发脚手架。
- 以 LangGraph 作为 Agent 工作流、恢复和 Multi-Agent 的核心运行时。
- Java Spring Boot 负责企业控制面、身份权限、业务事务、审计和代码生成编排。
- React + React Flow 负责可视化开发工作台，但不承担可信执行。
- Python FastAPI + LangGraph 负责 Agent、RAG、Memory、Tool Adapter、Model Gateway 和评测。
- 支持从可视化 Workflow IR 导出可维护、可独立运行的 LangGraph 源码工程。
- HR 工作台是脚手架的首个完整业务验收应用；当前非 HR Asset 生成物只是先行通用性小样。脚手架本体自主设计开发平台与代码生成器，Agent 画板是平台中的开发模块。
- Spring Boot 企业应用基座、Agent 快速搭建和低代码 Java / Python / Web 源码生成是三个产品能力；企业后台与 CRUD 生成体验只参考若依的设计理念，本项目不是若依的 Fork 或二次开发。

## 当前已确认决策

- 默认架构从“Java 模块化单体控制面 + 独立 Python Agent Runtime + 自主实现的 React 平台管理端（内含 Agent Studio）”开始，保留按边界拆分微服务的能力。
- 画布保存 UI Graph，服务端转换并校验 Canonical Workflow IR；生产运行时不直接信任 React Flow JSON。
- 文本处理分为确定性 Text Processing、模型型 NLP、LLM Operation 和 RAG 节点，不使用含义模糊的单一 NLP 分类。
- 知识摄取采用 G0 Source Admission 至 G5 Index Admission 六道质量门。
- RAG 评测覆盖 Recall@K、Precision@K、MRR、nDCG、忠实度、引用支持率、延迟、费用和索引资源。
- HNSW `M` / `efConstruction` 通过候选索引比较，`efSearch` 作为查询期参数实验。
- 生成器属于开发工具；生成物可以脱离平台独立运行，并保留模板和 IR 版本用于升级。
- `ProjectDocs/项目总地图.md` 是合并两份开发地图与 Agent 组件需求后的唯一脚手架实施顺序；上层 LangGraph 项目文档仅作迁移依据，教学流程不约束本仓库开发。
- MCP Scale 指多 MCP 服务规模化接入与治理；RAG Lab 分别配置检索 TopK、索引构建参数 `M` / `efConstruction` 和查询参数 `efSearch`。
- 每个交付版本先记录范围与验证证据，再提交并推送到 `YuyanYYt/Yuan_Scaffold`；规划文档与已实现功能分开标记。

## 当前状态

- GitHub 公开仓库和本地仓库已建立。
- `ProjectDocs/项目总地图.md` 已整合产品与项目集、Spring Boot 企业能力、Agent 全组件、MCP Scale、RAG 参数实验、六条链路、Workflow IR、首个完整 HR 业务验收应用和主版本覆盖矩阵。
- `ProjectDocs/ReactFlow与NLP节点调研.md` 已创建。
- `packages/workflow-contracts/` 已建立 Workflow IR v0.1、11 种内置节点的声明式注册表、两个 HR 草稿样例和静态校验器；28 项测试通过。该契约包自身不可发布或执行工作流。
- `services/agent-runtime-python/` 已建立受限六节点 LangGraph 运行包，使用 SQLite Checkpoint、节点级脱敏 Trace 和合成测试适配器；10 项测试通过。它不包含生产检索、模型、授权或答案门适配器。
- `packages/app-generator/` 已建立单实体 Manifest 生成器；`examples/asset-app/` 是可独立运行的非 HR Java / React 生成样例，已完成后端测试、前端构建、HTTP 与浏览器验收。
- `services/platform-java/` 已建立 Spring Boot 4.1.1 / Java 21 企业基座，含认证、租户、用户 / 角色 / 菜单创建与读取、CSRF、Flyway、事务审计、健康检查及 HMAC Agent 客户端；12 项 Java 测试通过。
- `services/agent-api-python/` 已建立 FastAPI 内部协议边界，包含验签、授权绑定、幂等、状态和明确不可用响应；12 项测试通过。Java 固定签名向量与 Python 一致，真实本地 Java→FastAPI HTTP 联测 3 项通过；成功路径只用测试专用执行器。
- 当前 LangGraph 项目中已有的 Runtime、Tool、RAG 摄取和质量门代码尚未复制，只登记为迁移候选。
- v0.3 约定的最小验收门已通过，仍是 `0.3.0-SNAPSHOT`，未建立正式发布标签；独立 React 平台管理端、可信 ACTIVE Release Catalog、真实 Agent 适配器及完整 HR 应用尚未实现。

## 当前主线

- v0.4 可视化开发纵切：建立独立平台管理端及 React Flow → Canonical IR → 受限调试，统一生成应用与 Java 企业基座的安全、租户契约；真实 Agent 问答留到 v0.5 的可信 Release Catalog 与适配器闭环。

## 待确认

- 项目正式品牌名；当前以仓库名 `Yuan_Scaffold` 和工作名 Yuan Scaffold 标识。
- 开源许可证。
- 下一组正式支持的 Java、Node.js、Python、Spring Boot、React、LangGraph 版本范围；当前测试组合已登记在总地图第 11.3 节。
- 数据库、向量后端、队列和对象存储的首选实现。
- 第一版是否同时生成员工端和管理端，或先完成员工端 + 最小管理入口。
