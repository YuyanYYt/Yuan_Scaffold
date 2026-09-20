# 项目记忆

> 最后核对：2026-09-20  
> 作用域：仅 `java-agent-scaffold` 仓库。

## 稳定目标

- 构建开源企业级 Java + AI Agent 智能应用开发脚手架。
- 以 LangGraph 作为 Agent 工作流、恢复和 Multi-Agent 的核心运行时。
- Java Spring Boot 负责企业控制面、身份权限、业务事务、审计和代码生成编排。
- React + React Flow 负责可视化开发工作台，但不承担可信执行。
- Python FastAPI + LangGraph 负责 Agent、RAG、Memory、Tool Adapter、Model Gateway 和评测。
- 支持从可视化 Workflow IR 导出可维护、可独立运行的 LangGraph 源码工程。
- HR 员工服务是脚手架生成的首个最终成品，不是平台核心中的硬编码业务。

## 当前已确认决策

- 默认架构从“Java 模块化单体控制面 + 独立 Python Agent Runtime + React Studio”开始，保留按边界拆分微服务的能力。
- 画布保存 UI Graph，服务端转换并校验 Canonical Workflow IR；生产运行时不直接信任 React Flow JSON。
- 文本处理分为确定性 Text Processing、模型型 NLP、LLM Operation 和 RAG 节点，不使用含义模糊的单一 NLP 分类。
- 知识摄取采用 G0 Source Admission 至 G5 Index Admission 六道质量门。
- RAG 评测覆盖 Recall@K、Precision@K、MRR、nDCG、忠实度、引用支持率、延迟、费用和索引资源。
- HNSW `M` / `efConstruction` 通过候选索引比较，`efSearch` 作为查询期参数实验。
- 生成器属于开发工具；生成物可以脱离平台独立运行，并保留模板和 IR 版本用于升级。

## 当前状态

- GitHub 公开仓库和本地仓库已建立。
- `ProjectDocs/项目总地图.md` 已创建，覆盖产品边界、模块、六条链路、Workflow IR、RAG、HR 成品和 S0-S13 顺序。
- `ProjectDocs/ReactFlow与NLP节点调研.md` 已创建。
- 当前 LangGraph 项目中已有的 Runtime、Tool、RAG 摄取和质量门代码尚未复制，只登记为迁移候选。
- 尚未创建 Java、Python 或 React 生产源码目录；总地图中的目录为目标结构。

## 当前唯一下一步

- S1：定义 Workflow IR v0.1，并用 HR 政策问答最小工作流验证跨语言图契约和 LangGraph 编译输入。

## 待确认

- 项目正式名称与品牌名；当前仓库名沿用 `java-agent-scaffold`。
- 开源许可证。
- Java、Node.js、Python、Spring Boot、React、LangGraph 的锁定版本。
- 数据库、向量后端、队列和对象存储的首选实现。
- 第一版是否同时生成员工端和管理端，或先完成员工端 + 最小管理入口。
