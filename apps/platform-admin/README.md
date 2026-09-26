# Yuan Scaffold 独立平台管理端（v0.4 纵切）

这个 React / Vite 管理端是 Yuan Scaffold 自主实现的开发平台界面。它参考若依的企业后台模块组织体验，但没有使用若依源码或样式，也不是若依的 Fork 或二次开发。HR 工作台是未来第一个完整业务验收应用；此处是可用于不同领域的脚手架平台。

## 运行

需要 Node 20.19+ 或 22.12+，以及正在运行的 `services/platform-java`。Java 首次引导账号与 H2 配置见该服务的 README。开发服务器把 `/api` 代理至 `APP_API_TARGET`，默认 `http://127.0.0.1:8080`。

```bash
cd apps/platform-admin
npm ci
npm run dev
npm test
npm run build
```

登录名是 `tenantSlug/username`。浏览器只在当前页面内存保留凭据，用 HTTP Basic 请求 `GET /api/v1/me` 和草稿接口；页面卸载或断开连接时丢失。写请求先获取 `GET /api/v1/csrf`，携带服务端给出的 Cookie 与请求头。当前协议仅适合本地开发验证；正式浏览器登录仍需要单独的会话、账号生命周期和部署安全验收。

## 当前功能

- 通过稳定游标分页读取租户内项目草稿并按需加载更多；创建与更新草稿使用 `expectedRevision` 防止覆盖并发修改。服务端的 401、403、409 和不可达错误直接显示。
- 表单配置项目坐标、一个实体、string / integer / boolean 字段、必填与唯一约束、读写权限。浏览器直接复用 `packages/app-generator/src/manifest.mjs` 的校验器，草稿 Manifest 与 v0.3 生成器契约一致。
- `POST /api/v1/code-previews` 从服务端生成真实文件内容、大小与 SHA-256 列表；界面显示原文件文本，并在配置改变后标记预览过期。`POST /api/v1/code-exports` 返回服务端生成的**源码草稿 ZIP**，响应头必须为 `X-Yuan-Source-Status: DRAFT_UNVERIFIED`，并提供 `X-Yuan-Export-Audit-Id`；浏览器按生成器 `artifactId` 下载。该次配置尚未逐项编译、测试或部署。失败时显示服务端错误，不本地生成假源码或 ZIP。
- React Flow 画板使用非 HR 的通用六节点示例。节点可拖动、添加、删除、连线；可编辑节点配置、端口、RAG TopK 和 `ef_search`，并声明索引 `M`、`efConstruction`。`POST /api/v1/workflow-drafts/validate` 通过 Java 控制面调用 `studio-graph`，只返回静态校验后的 `DRAFT` IR；该结果始终不可执行、不可发布。

画板样图在 `src/workflow-starter.json`。其资源 ID 是占位声明，不代表真实 Model、Prompt、知识库或 ANN 索引已经登记。当前画板状态只在页面内存，尚无服务端工作流草稿持久化或可信 Release Catalog。项目草稿 API 仅保存应用 Manifest，不会保存画板 JSON。切换项目或离开页面前会提示未保存工作流变化。

源码预览与导出要求 Java 服务配置 `PLATFORM_GENERATOR_CLI`，工作流校验要求配置 `PLATFORM_STUDIO_GRAPH_CLI`；否则服务端返回 503，界面明确显示。保存草稿、源码预览、ZIP 导出、DRAFT IR 静态校验是不同的结果，均不意味着 Agent 已接入真实模型、RAG 数据或发布链路。

React Flow 画板使用 [官方 `@xyflow/react` API](https://reactflow.dev/learn) 的受控节点和边；生成器 Manifest、工作流转换及执行语义以本仓库包契约为准。
