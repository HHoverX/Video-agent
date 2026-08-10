# VideoAgent AI Handoff

> 最后更新：2026-08-10
> 当前状态：Milestone 1–6.6 已完成并验收；Milestone 7 尚未开始。

## 1. 项目目标与工作原则

VideoAgent 是一个面向 Java 后端与 AI 应用工程学习的单体项目。当前重点是可读性、清晰的模块边界、可验证的工程取舍和逐里程碑交付，而不是过度优化或提前扩展架构。

每个 Milestone 都遵循：

```text
Plan → Implementation → Tests → Real Acceptance → Acceptance Report → Git Checkpoint
```

完成后必须停止，不得自动进入下一 Milestone；不得修改已经验收的历史 Flyway migration。

## 2. 当前交付状态与 Git checkpoint

- 当前分支：`main`
- M6.6 最终业务 checkpoint：`6c5d4a8b8602baef23fc72bb98abe0251925e8a3`
- Commit：`feat: complete milestone 6.6 authentication and video management`
- 上一个 M6.5 checkpoint：`13f0daa feat: complete milestone 6.5 real ai integration`
- M7 尚未开始，没有 M7 业务代码。
- 本文档更新应作为 M6.6 之后的独立 docs commit，不与业务实现混合。

## 3. 已完成 Milestone

| Milestone | 已完成能力 |
| --- | --- |
| M1 — Infrastructure | Spring Boot / Vue 工程骨架；MySQL、Redis、MinIO、RocketMQ Docker Compose 基础设施；健康检查。 |
| M2 — Video Upload | MP4 multipart 上传；基础类型与大小校验；MinIO 对象存储；MySQL 视频元数据；视频列表与详情。 |
| M3 — Async Analysis Framework | `analysis_task`、RocketMQ Producer/Consumer、Redis 实时进度、MySQL 持久状态、任务查询、Consumer 幂等和前端轮询。 |
| M4 — FFmpeg + Mock ASR | MinIO 下载、FFmpeg 提取 WAV、临时文件清理、`AsrProvider`、确定性 Mock ASR、带时间戳 transcript segments 与 Transcript API。 |
| M5 — Structured Video Summary | `VideoSummaryProvider`、Mock / LangChain4j Provider、Structured Output 校验、Overview、Chapters、Key Points 持久化和前端展示。 |
| M6 — SSE Real-time Progress | Spring MVC `SseEmitter` 实时进度、终态关闭、断开清理、Redis 缺失时 MySQL 初始化、GET polling fallback。 |
| M6.5 — Real AI Integration | Real ASR/LLM Provider 接入；DashScope、Groq ASR 实现；DeepSeek OpenAI-compatible LLM；真实 AI smoke-test 门控；无音轨错误分类。 |
| M6.6 — Authentication & Video Management | 注册登录、JWT、用户 ownership、Video CRUD、分页与标题模糊查询、携带 JWT 的 fetch-based SSE、完整浏览器与真实 AI 验收。 |

M1–M6.6 的自动化测试、真实基础设施测试和前端构建均已通过。用户已在浏览器中确认注册、登录、上传、分页/查询、修改、删除、分析、SSE、Transcript/Summary，以及正确配置环境变量后的 DashScope + DeepSeek 真实链路。

## 4. 当前端到端链路

```text
Vue Browser
  → JWT-authenticated Spring Boot REST API
  → MinIO video object + MySQL video metadata
  → MySQL PENDING analysis_task
  → RocketMQ VIDEO_ANALYZE_TOPIC
  → Consumer claim / idempotency guard
  → MinIO download
  → FFmpeg audio extraction
  → AsrProvider (Mock / DashScope / Groq)
  → timestamp transcript segments
  → VideoSummaryProvider (Mock / LangChain4j OpenAI-compatible)
  → overview / chapters / key points
  → MySQL persistent result + Redis live progress
  → Spring MVC SSE
  → authenticated fetch + ReadableStream frontend
```

SSE 只是观察通道。浏览器断开不会中止后台任务；普通 `GET /api/analysis/{taskId}` 始终保留为恢复、调试和 polling fallback。

## 5. 当前完整技术栈

### Backend

- Java 21
- Spring Boot 3.5.5
- Spring MVC / `SseEmitter`
- Spring Security（无状态 Bearer JWT）
- JJWT 0.13.0
- Spring Validation / Actuator
- MyBatis-Plus 3.5.12
- Flyway
- Maven
- JUnit 5 / Spring Boot Test / Mockito

### Data & Infrastructure

- MySQL 8.4
- Redis 7.4
- RocketMQ 5.3.2（NameServer + Broker）
- MinIO
- Docker Compose

### Media & AI

- FFmpeg：从上传视频提取单声道 WAV；处理超时、非零退出、stderr、无音轨和临时文件清理。
- DashScope ASR：当前已人工验收的真实中文语音识别 Provider。
- Groq Speech-to-Text：保留的可选 Real ASR Provider。
- Mock ASR：默认本地开发、CI 和自动化测试使用。
- LangChain4j 1.18.0：封装 OpenAI-compatible Structured Output 调用。
- DeepSeek：通过 OpenAI-compatible LangChain4j Provider 使用，不存在 DeepSeek 专用业务层。
- Mock Summary Provider：无 API Key 的本地开发、CI 和自动化测试使用。

### Frontend

- Vue 3.5
- TypeScript 5.7
- Vite 6.2
- Vue Router 4.5
- Pinia 3
- Element Plus 2.9
- Axios 1.8（常规 REST 请求及 Bearer Token interceptor）
- Browser Fetch API + `ReadableStream` + `AbortController`（authenticated SSE）

## 6. Authentication 与 ownership

### JWT authentication

- `POST /api/auth/register`：校验用户名和密码，使用 BCrypt 保存密码哈希。
- `POST /api/auth/login`：校验凭证并返回 JWT；错误统一为 `INVALID_CREDENTIALS`，避免账户枚举。
- `GET /api/auth/me`：从 Spring Security `SecurityContext` 读取当前用户。
- JJWT 校验 HMAC 签名与过期时间；`JWT_SECRET`、`JWT_EXPIRATION` 仅通过环境配置提供。
- API 使用 `SessionCreationPolicy.STATELESS`，不使用 Redis Session。
- 前端 Pinia 保存认证状态，JWT 当前存入 `localStorage`；Axios 自动附加 Bearer Token，并处理 401。
- 当前没有 refresh token、服务端 blacklist、主动撤销或多设备 session 管理。前端 logout 只清除本地认证状态。

### Video ownership

- 上传时，`video.user_id` 来自已认证 principal，客户端不能提交或覆盖 `userId`。
- 视频列表、详情、修改和删除始终按 `video.id + currentUserId` 查询。
- Analysis、SSE、Transcript、Summary、Chapters、Key Points 均通过 video ownership 做授权。
- 跨用户访问统一返回 404，避免泄露资源是否存在。
- 当前只有认证和资源 ownership，没有 RBAC、role、ACL、管理员或 OAuth2/SSO。

## 7. Video CRUD、分页与模糊查询（pagination / fuzzy search）

- `POST /api/videos`：上传视频到 MinIO，并在 MySQL 保存 owner-scoped metadata。
- `GET /api/videos`：分页查询当前用户的视频。
- `GET /api/videos/{videoId}`：查询当前用户的视频详情。
- `PATCH /api/videos/{videoId}`：仅允许修改经过 trim 的 `title`。
- `DELETE /api/videos/{videoId}`：有 PENDING/PROCESSING task 时返回 409；无活动任务时删除数据库数据并尽力清理 MinIO 对象。

分页和查询规则：

- 参数：`page`、`size`、可选 `keyword`。
- 默认 page 1、size 10；最大 size 50。
- `keyword` 仅对标题执行 `LIKE '%keyword%'`。
- 固定 owner scope，按 `created_at DESC, id DESC` 稳定排序。
- 返回 `items`、`page`、`size`、`total`、`pages`。
- 当前规模没有引入 Elasticsearch；领先通配符查询也没有伪装成可受普通 B-tree title index 加速。

## 8. Authenticated SSE

原生 `EventSource` 不能设置 `Authorization` header，因此 M6.6 前端改用 `fetch`：

1. 请求 `GET /api/analysis/{taskId}/events` 并携带 `Authorization: Bearer <JWT>`。
2. 使用 `ReadableStream` 解析 SSE frame、`event: progress` 和多行 `data`。
3. SUCCESS / FAILED 时关闭；组件卸载时用 `AbortController` 主动断开。
4. 连接失败时有限退化为现有 GET polling。
5. 服务端创建 emitter 前验证 task 对应 video ownership。

Token 不放入 URL。SSE 断开、超时或页面刷新不改变 RocketMQ Consumer 和 analysis task 的执行。

## 9. MySQL / Redis / RocketMQ / MinIO 职责

| 组件 | 当前职责 | 明确不承担的职责 |
| --- | --- | --- |
| MySQL | 用户、视频 metadata/object key、analysis task 持久状态、transcript segments、summary、chapters、key points；最终业务事实源。 | 不存视频二进制；不依赖 Redis 判定最终成功。 |
| Redis | `video:analysis:progress:{taskId}` 实时进度快照和 TTL；查询失败或 key 丢失时允许回退 MySQL。 | 不是任务最终事实源；不存 session；不使用 Pub/Sub 驱动 SSE。 |
| RocketMQ | `VIDEO_ANALYZE_TOPIC` 异步投递 `{taskId, videoId}`；解耦 HTTP 与长时间媒体/AI 处理。 | 不传视频二进制、transcript 或大对象；当前没有多 Topic 流水线。 |
| MinIO | 保存原始上传视频；Consumer 通过 `ObjectStorageService` 下载；删除视频后清理对象。 | 不保存业务状态、ownership 或分析结果。 |

## 10. 当前 Provider 与基础边界

### Storage / media

- `ObjectStorageService`：上传、下载、删除对象；具体 MinIO SDK 调用收敛在 `MinioStorageService`。
- `MediaProcessor`：只定义 video → audio；当前实现为 `FfmpegMediaProcessor`。
- `TemporaryMediaWorkspace` / `MediaWorkspace`：每个 task 独立临时目录，使用 try-with-resources 清理。

### ASR

```text
AsrProvider
├── MockAsrProvider
├── DashScopeAsrProvider
└── GroqAsrProvider
```

- 输入：`AudioSource`。
- 输出：`TranscriptionResult`，包含有序的 `TranscriptSegment(startMs, endMs, text)`。
- `AsrResultValidator` 拒绝空 segments、空文本、非法时间区间、非单调时间和明显超出音频时长的结果。
- `ASR_PROVIDER=mock|dashscope|groq` 负责切换；API Key、model、base URL、timeout 均来自环境变量。
- 当前真实中文链路使用 DashScope，并已由用户人工验收；自动化测试仍默认使用 Mock，不调用付费网络服务。

### Structured summary

```text
VideoSummaryProvider
├── MockVideoSummaryProvider
└── LangChain4jVideoSummaryProvider
```

- 输入：`VideoSummaryRequest(videoId, taskId, transcriptSegments)`。
- 输出：`VideoSummaryResult(overview, chapters, keyPoints)`。
- LangChain4j Provider 使用 OpenAI-compatible client；当前真实配置指向 DeepSeek，不在 Consumer/Service 中直接调用厂商 SDK。
- Structured Output 字段保持固定；`SummaryResultValidator` 校验非空、排序和 timestamp 边界。
- Prompt 要求 overview、chapter title/summary、key point 内容默认输出简体中文；专有名词和代码标识符可保留原文，不增加二次翻译调用。
- `LLM_PROVIDER=mock|openai` 切换；缺少真实配置时回退 Mock。API Key、model、base URL、timeout、有限 max retries 均来自环境变量。

## 11. Analysis task 与结果模型

- MySQL 状态：`PENDING → PROCESSING → SUCCESS | FAILED`。
- 主要阶段：`QUEUED`、`PREPARING`、`EXTRACTING_AUDIO`、`TRANSCRIBING`、`SAVING_TRANSCRIPT`、`SUMMARIZING`、`SAVING`、`DONE` / `FAILED`。
- Consumer 只接收 `taskId` 和 `videoId`，通过条件更新抢占 PENDING task。
- SUCCESS task 或不可抢占 task 的重复消息会直接跳过。
- 对同一 video / analysis type / model version 的有效任务有重复创建保护。
- Transcript 以带时间戳 segments 存储；chapters 和 key points 分表存储，不使用不可查询的大 JSON blob。
- 结果 API 返回最新成功的结构化分析结果，不直接暴露 Entity。

## 12. 已知 consistency boundaries

这些是当前明确接受的边界，不应被误写成“强一致”或在未进入 M7 时擅自重构：

1. **MySQL 与 Redis 非原子**：MySQL 是持久事实源；Redis 写入失败只影响实时性，GET/SSE 初始化可回退 MySQL。
2. **MySQL 与 RocketMQ 无 Transactional Outbox**：task 先持久化再同步发送消息；明确的发送失败会把 task 标记 FAILED，但进程在数据库提交与消息确认之间崩溃仍是跨系统窗口。
3. **MySQL 与 MinIO 非原子**：上传先写对象，数据库失败时尽力补偿删除；删除先提交数据库，再 best-effort 删除对象。补偿/清理失败可能留下 orphan object。
4. **分析结果分阶段持久化**：transcript、summary 和 task SUCCESS 不是一个覆盖外部 ASR/LLM 的全局事务；后续阶段失败时可能保留 task-scoped 中间数据，但面向用户的 summary 查询只选择成功任务结果。
5. **SSE 是进程内观察通道**：当前 `SseEmitter` subscriptions 存于单体实例内存，没有 Redis Pub/Sub；客户端断开不影响任务，丢失事件通过 GET/Redis/MySQL 当前状态恢复。多实例广播尚未设计。
6. **Consumer 幂等是 task 状态级别**：PENDING 条件抢占和 SUCCESS skip 防止正常重复消费重跑；没有复杂分布式锁、多 Topic 编排或 outbox。
7. **JWT 是无状态的最终过期模型**：服务器不维护 revoke list；logout 后已签发 token 在过期前仍可被验证。`localStorage` 存储也存在已知 XSS 权衡。
8. **历史 ownership 数据不回填**：M6.6 之前 `user_id` 为 null 或不匹配的历史视频对新用户不可见。
9. **标题模糊查询是当前规模方案**：`LIKE '%keyword%'` 不承诺大规模全文检索性能或相关性排序。

## 13. 配置与秘密管理

- `.env` 已被忽略且不得提交。
- `.env.example` 只保留占位值和安全示例。
- JWT Secret、ASR/LLM API Key、Token、真实密码不得写入源码、测试、日志、报告或 handoff。
- 自动化测试默认使用 Mock Provider；真实 AI smoke test 只有在显式设置 `VIDEOAGENT_REAL_AI_TEST=true` 且本地提供必要环境变量时才运行。
- DashScope + DeepSeek 的真实链路已由用户在浏览器中确认。此前 DashScope 失败是本地 PowerShell 缺少 ASR 环境变量，不是代码缺陷。

## 14. M7 状态与预计目标

**M7 尚未开始。** 当前不得把本节目标当作已实现，也不得在没有新的明确指令时修改 M7 代码。

规格中的 Milestone 7 主题是 **Reliability（可靠性）**，预计只在保持 M1–M6.6 架构稳定的前提下评估和补强：

- Consumer 幂等；
- 重复分析保护；
- 第三方 API timeout；
- 有界 retry；
- 稳定、可理解的错误码；
- 必要且不泄密的结构化日志；
- 对应单元测试和真实基础设施集成测试。

M7 开始前应先检查当前已有能力与缺口，提出最小实施计划，再编码。不得借 Reliability 名义引入微服务、Kafka、Kubernetes、Redis Pub/Sub、复杂分布式锁、Transactional Outbox、RAG、Embedding、Agent Loop 或其他超出规格的重构。

## 15. 后续接手规则

- 先阅读 `VideoAgent_Codex_Spec.md`、最近 acceptance report、当前 Git 状态和相关测试。
- 保留单体架构、Provider 边界以及 MySQL / Redis / RocketMQ / MinIO 的职责划分。
- 不修改历史 migration；新增 schema 必须新建 Flyway migration。
- Controller 保持薄；外部厂商调用只能位于 Provider 实现。
- 每个 Milestone 完成后执行编译、完整测试、回归、真实验收、报告和独立 Git checkpoint，然后停止。
- 当前停止点为 M6.6。等待用户明确授权后，才可以开始 M7。
