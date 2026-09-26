# Yuan Scaffold Java 企业基座（v0.3 起步）

这是本仓库自主实现的 Spring Boot 控制面基础包。若依仅提供后台组织和代码生成体验的设计参考；本包没有引用若依源码，也不是其 Fork 或二次开发。

## 当前已实现

- Spring Boot 4.1.1 / Java 21 / Maven 项目，可独立构建为可执行 JAR；Web MVC API、Bean Validation、统一业务错误响应。
- JDBC 关系模型与 Flyway V1 迁移。用户、角色、菜单、关联和审计表均带 `tenant_id`；关联表使用 `(tenant_id, id)` 组合外键约束。
- 数据库账号认证，BCrypt 密码哈希；HTTP Basic 用户名格式为 `tenantSlug/username`。租户 ID 来自认证后的主体，客户端提交的 `X-Tenant-ID` 不参与授权。
- 用户、角色、菜单的创建与读取，角色分配、菜单授权，按权限码控制 API，事务内记录管理操作审计。
- `GET /actuator/health` 健康检查；仅公开该 Actuator 端点。
- 对有副作用的 HTTP 方法启用 CSRF 校验。认证后 `GET /api/v1/csrf` 返回令牌并设置 `HttpOnly` 的 `XSRF-TOKEN` Cookie；后续请求同时携带 Cookie 和返回的 `X-XSRF-TOKEN` 请求头。
- 一次性首租户引导：仅空库时由环境变量创建首租户、管理员、权限菜单和角色。重启不会修改已有管理员密码。
- Java→Python 内部调用库：`AgentInvocationClient` 通过 Spring `RestClient` 向 `/internal/v1/runs`、状态查询和恢复端点发送 HMAC v1 断言。调用时只从已认证 `TenantPrincipal` 取租户与用户，必须由宿主注入 `TrustedReleaseCatalog` 返回绑定该用户、工作流、run/thread 和四类资源 grant 的服务端授权。断言签名绑定 HTTP 方法、原始路径和请求体 SHA-256；无密钥或 Catalog 时不能构造可调用客户端。

## 运行

`mvn test` 运行集成测试；`mvn package` 构建；`java -jar target/platform-java-0.3.0-SNAPSHOT.jar` 启动。默认数据库为当前目录下的持久化 H2 文件 `data/platform-db`；`target/` 与 `data/` 在本包的 `.gitignore` 中。

首次启动前设置以下环境变量（密码从本地密钥管理方式注入，勿提交到仓库）：

| 变量 | 用途 |
| --- | --- |
| `PLATFORM_BOOTSTRAP_TENANT` | 首租户 slug，例 `demo` |
| `PLATFORM_BOOTSTRAP_TENANT_NAME` | 可选显示名 |
| `PLATFORM_BOOTSTRAP_USERNAME` | 首个管理员用户名 |
| `PLATFORM_BOOTSTRAP_PASSWORD` | 至少 12 字符的初始密码 |
| `PLATFORM_JDBC_URL`、`PLATFORM_JDBC_USERNAME`、`PLATFORM_JDBC_PASSWORD` | 可选外部数据库连接配置 |

引导变量缺失时应用仍能启动并提供健康检查，但不会创建可登录账号。引导只适用于空库；已有数据的租户开通和凭据轮换尚未实现。使用 PostgreSQL 前需单独验证迁移、备份与部署配置，当前集成测试使用 H2。

## API 边界

| 方法与路径 | 权限或行为 |
| --- | --- |
| `GET /api/v1/me` | 返回已认证的租户、用户和权限码 |
| `GET /api/v1/csrf` | 返回 CSRF 请求头名与令牌，设置 Cookie |
| `GET /api/v1/users`、`GET /api/v1/users/{id}` | `system:user:read` |
| `POST /api/v1/users` | `system:user:write` |
| `GET /api/v1/roles`、`GET /api/v1/roles/{id}` | `system:role:read` |
| `POST /api/v1/roles`、`POST /api/v1/users/{userId}/roles/{roleId}` | `system:role:write` |
| `GET /api/v1/menus`、`GET /api/v1/menus/{id}` | `system:menu:read` |
| `POST /api/v1/menus` | `system:menu:write`；权限码须为当前内置权限之一 |
| `POST /api/v1/roles/{roleId}/menus/{menuId}` | `system:role:write` |
| `GET /api/v1/audit?limit=50` | `system:audit:read`；上限 100 |

列表接口当前最多返回 100 条，没有分页游标。跨租户资源按不存在返回 404。写接口的用户字段不接受 `tenant_id`，审计不写入明文密码。HTTP Basic 是本版的 API/开发验证入口，必须经 HTTPS 终止；**尚不适合作为生产浏览器管理端的完整登录方案**。正式管理端仍需独立完成会话或令牌登录、账号生命周期、MFA、限流和安全部署验收。

## 验证与未完成范围

`PlatformIsolationIntegrationTest` 覆盖：Flyway 迁移、租户头伪造与跨租户读取、跨租户角色和菜单赋权拒绝、组合外键层隔离、权限与认证区分、CSRF 拒绝及令牌成功提交、统一错误与租户审计隔离。`AgentProtocolTest` 直接读取 Python 包的 `tests/vectors/hmac-v1.json`，校验 Java 生成的断言与签名和 Python 固定向量逐字节一致；本地 HTTP stub 验证 start/status/resume 路径、原始体哈希和身份绑定。当前 `mvn test` 为 12 项通过。

内部客户端**没有自动注册为 Bean**，也没有对浏览器开放启动 Agent 的 Controller。调用方须先实现可信 Release Catalog、会话/线程 ACL、密钥配置与轮换，然后显式构造客户端并保存 `run_id`、`thread_id` 和 `request_id` 供幂等重试。当前只有 HMAC 协议和本地 stub 互通证据；Python 端默认没有真实 Catalog 和执行后端，因此不能宣称 Agent 业务链路已经打通。

本包尚未实现 ABAC、部门/岗位、字典/参数、任务、正式租户开通、用户/角色/菜单的更新和删除、权限撤销 API、登录和拒绝请求审计、生产数据库及安全部署验证、可信 Release Catalog 与真实 Agent 后端接线、平台管理端、通用代码生成器和全部 Spring Boot 能力。它是 v0.3 企业最小闭环的 Java 基座切片，不能代表 v0.3 整体验收完成。

技术版本和功能边界参考 [Spring Boot 4.1.1 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Spring Boot Starter 目录](https://docs.spring.io/spring-boot/reference/using/build-systems.html)、[Spring Boot Flyway 初始化](https://docs.spring.io/spring-boot/how-to/data-initialization.html) 与 [Spring Security CSRF 文档](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)。
