# VideoAgent v1.0.0 FINAL AI Handoff

> 最后更新：2026-08-13
> 最终基线：`v1.0.0` / `0d04a7fe73baf6f7309f31214687c78be736b0c4`
> 最终提交：`fix: close milestone 8.2 audit findings`
> 项目状态：**Feature Complete / Frozen**

## 1. 文档用途与事实优先级

本文档用于让新的 AI / Codex 会话快速理解 VideoAgent v1.0.0 的最终实现，而不是记录逐日开发过程或规划后续功能。

事实优先级：

1. 当前 `main@v1.0.0` 的源码、Flyway migration、配置和测试；
2. 最终 Acceptance Report 与 Code Audit；
3. 本文档和其他说明文档。

如果文档与代码不一致，以当前源码为准并明确指出差异。各 Milestone 报告中的“下一里程碑尚未开始”只描述报告产生时的历史状态，不代表 v1.0.0 现状。

已知文档状态：

- `AI_HANDOFF.md` 已按 v1.0.0 重写；
- `README.md` 的能力清单仍停留在 M6.6 时期，未覆盖最终 RAG / Agent 实现，不能据此判断 v1.0.0 能力；
- 若本地存在 `MILESTONE_7_CODE_AUDIT.md`：该文件未被 v1.0.0 Git 跟踪，且记录的是 M7 修复前问题，只能作为历史辅助材料；
- `MILESTONE_8_2_CODE_AUDIT.md` 记录审计发现，最终提交 `0d04a7f` 随后关闭了相关问题，不能把审计时的 `CONDITIONAL PASS` 直接当作最终源码状态。

## 2. 项目定位与最终范围

VideoAgent 是一个面向 Java 后端与 AI Application 工程学习的**模块化单体**项目。核心目标是展示可解释、可验证的视频上传、异步分析、可靠性、RAG 和受控 Agentic Retrieval，而不是追求大规模生产集群或通用自主 Agent。

最终完成范围：

| Milestone | 最终能力 |
| --- | --- |
| M1 | Spring Boot / Vue 工程；MySQL、Redis、MinIO、RocketMQ Docker Compose 基础设施 |
| M2 | MP4 上传校验；MinIO 对象；MySQL 视频元数据；视频列表与详情 |
| M3 | `analysis_task`、RocketMQ Producer/Consumer、Redis 实时进度、MySQL 持久状态 |
| M4 | FFmpeg 提取音频；Mock / Real ASR；带时间戳 Transcript |
| M5 | LangChain4j Structured Summary；Overview、Chapters、Key Points |
| M6 | Spring MVC SSE 实时进度与 GET polling fallback |
| M6.5 | Real Provider 接入：DashScope ASR、Groq ASR adapter、OpenAI-compatible LLM；最终真实验收采用 DashScope + DeepSeek |
| M6.6 | JWT、用户 ownership、Video CRUD、分页/模糊查询、Authenticated SSE |
| M7 | Transactional Outbox、原子 Claim、有界重试、恢复、Heartbeat、Generation Fencing、Durable Resume |
| M8.1 | Adaptive Basic RAG：DIRECT_CONTEXT 或 Chunk / Embedding / Qdrant / Top-K |
| M8.2 | 有界 Agentic Retrieval：Planner、Validator、三种 Tool、Evidence 与 Citation Validation |

v1.0.0 已封板：不进入 M8.3 / M9，不继续增加功能，不以“优化”为由改造架构或引入新技术栈。后续目标是学习源码、稳定演示、故障排查和面试准备。

## 3. 当前技术栈与运行拓扑

### Backend

- Java 21
- Spring Boot 3.5.5、Spring MVC、Spring Security、Validation、Actuator
- MyBatis-Plus 3.5.12、Flyway、MySQL 8.4
- Spring Data Redis、Redis 7.4
- RocketMQ 5.3.2（NameServer + 单 Broker）；RocketMQ Spring Starter 2.3.2
- MinIO Object Storage
- LangChain4j 1.18.0、OpenAI-compatible Chat / Embedding API
- Qdrant 1.12.4（REST adapter）
- Maven、JUnit 5、Spring Boot Test、Mockito

### Media / AI

- FFmpeg：video → 单声道 WAV
- ASR：Mock、DashScope、Groq
- LLM：Mock 或 OpenAI-compatible；已验收配置使用 DeepSeek
- Embedding：Mock 或 OpenAI-compatible；支持 `openai` / `dashscope` 配置名
- Basic QA、Retrieval Planner、Agentic Answer 均有可测试的 Mock 边界

### Frontend

- Vue 3、TypeScript、Vite
- Vue Router、Pinia、Element Plus、Axios
- Fetch API + `ReadableStream` + `AbortController` 实现携带 Bearer JWT 的 SSE

### 本地运行拓扑

```text
Windows Host
├── Vue dev server
├── Spring Boot API :8080
└── Docker Compose: videoagent
    ├── MySQL
    ├── Redis
    ├── MinIO
    ├── RocketMQ NameServer
    ├── RocketMQ Broker
    └── Qdrant
```

Spring Boot 与 Vue 默认在 Host 运行，通过宿主机端口连接 Docker 中间件；Compose 不包含后端或前端容器。

## 4. 最终运行架构

### 视频上传与分析主链

```text
Vue Browser
  → Bearer JWT / Spring Security
  → Video ownership
  → MinIO video object + MySQL video metadata
  → one MySQL transaction: PENDING analysis_task + dispatch outbox event
  → OutboxPublisher polling
  → RocketMQ VIDEO_ANALYZE_TOPIC
  → Consumer conditional claim
  → MinIO download
  → FFmpeg audio extraction
  → AsrProvider
  → timestamp transcript segments
  → VideoSummaryProvider
  → overview / chapters / key points
  → MySQL durable state + Redis live progress
  → SSE / GET polling
```

### M8.1 Basic QA

```text
Question + owned video
  → load latest successful Transcript
  → transcript chars <= threshold: DIRECT_CONTEXT → full segments → LLM
  → transcript chars > threshold: RAG → query embedding → Qdrant top-K → LLM
  → backend citation mapping from supplied Segment / retrieved Chunk metadata
```

### M8.2 Agentic Retrieval

```text
Question + server-bound context
  → RetrievalPlannerProvider
  → RetrievalPlanValidator
  → one bounded Tool execution pass
  → EvidenceNormalizer
  → AgenticAnswerProvider
  → backend Citation Validation
```

它只有一次规划、一次执行、一次合成；没有 ReAct 循环、反思循环或自主扩权。

## 5. MySQL / Redis / RocketMQ / MinIO / Qdrant 职责

| 组件 | 当前职责 | 明确边界 |
| --- | --- | --- |
| MySQL | 用户、视频 metadata / object key、任务状态、Outbox、Transcript、Summary、RAG index 生命周期；最终业务事实来源 | 不保存视频二进制；不能与 RocketMQ、MinIO、Qdrant 做一个原子事务 |
| Redis | `video:analysis:progress:{taskId}` 实时进度快照与 TTL | Best-effort cache；不参与 Claim、Retry、Resume、Recovery、Fencing 或终态判断；不保存 Session |
| RocketMQ | At-least-once 异步传递分析消息，解耦 HTTP 与长耗时媒体/AI 处理 | 可能重复投递；不传视频、Transcript 或大对象；不是 Exactly Once |
| MinIO | 保存原始上传视频，供分析 Worker 下载 | 不保存 ownership、任务状态或分析结果；删除后的对象清理是 best-effort |
| Qdrant | 保存 Transcript Chunk 的向量和检索 metadata，执行相似度搜索 | 是可从 MySQL Transcript 重建的 Derived Index，不是业务事实来源 |

SSE 是进程内观察通道。浏览器断开、SSE 发送失败或 Redis key 丢失不会取消后台任务；查询可回退 MySQL。

## 6. Authentication、Ownership 与信任边界

```text
Authorization: Bearer <JWT>
  → JwtAuthenticationFilter 验证签名与过期时间
  → Authentication 写入 SecurityContext
  → CurrentUserAccessor 取得 currentUserId
  → Service 按 videoId + currentUserId 检查 ownership
```

- 注册密码使用 BCrypt 哈希；登录失败统一为 `INVALID_CREDENTIALS`。
- API 使用无状态 JWT，不使用 Redis Session。
- 上传视频的 `user_id` 来自认证上下文，客户端不能指定或覆盖。
- Video、Analysis、SSE、Transcript、Summary、RAG、Agentic QA 均在服务端执行 ownership 检查。
- 跨用户资源访问统一返回 404，避免泄露资源是否存在。
- Planner schema 和 Tool 参数不包含 `userId` / `videoId`；LLM 不能选择身份、资源或权限。
- M8.1 / M8.2 的检索隔离由两层约束组成：先在 MySQL 执行 video ownership 校验，再在 Qdrant payload filter 中同时约束 `userId = currentUserId` 与 `videoId = 当前视频`。不能只依赖向量相似度或 Planner 输出限定检索范围。
- 当前没有 RBAC、ACL、管理员、OAuth2/SSO、refresh token、服务端 revoke list 或多设备 Session 管理；前端 JWT 存在 `localStorage` 的 XSS 权衡。

## 7. Provider 边界

### Storage / Media

- `ObjectStorageService` 隔离 MinIO SDK；当前实现为 `MinioStorageService`。
- `MediaProcessor` 定义 video → audio；当前实现为 `FfmpegMediaProcessor`。
- `MediaWorkspace` 为每个任务创建独立临时目录，并通过 try-with-resources 清理。

### ASR

```text
AsrProvider
├── MockAsrProvider
├── DashScopeAsrProvider
└── GroqAsrProvider
```

- `ASR_PROVIDER=mock|dashscope|groq`。
- 显式选择真实 ASR 但缺少 API Key、Model 或 Base URL 时启动失败，不静默降级。
- `AsrResultValidator` 校验空结果、时间范围、顺序和明显越界。

### Summary / Basic QA / Agentic Answer

- `VideoSummaryProvider`：Mock 或 LangChain4j OpenAI-compatible Structured Output。
- `VideoQaProvider`：M8.1 Basic QA 的 Mock 或 LangChain4j 实现。
- `AgenticAnswerProvider`：M8.2 Evidence 合成的 Mock 或 LangChain4j 实现。
- 全量 v1.0.0 应用中，`LLM_PROVIDER=openai` 缺少 API Key 或 Model 会由 Agentic Answer 配置 fail-fast；不要依赖旧 Summary / Basic QA 配置类的局部 Mock fallback。
- 模型输出始终视为不可信：结构、字段、Evidence ID 和 Citation 均需后端约束或映射。

### Embedding

- `EMBEDDING_PROVIDER=mock|openai|dashscope`。
- 真实 Provider 使用 OpenAI-compatible `/embeddings`，配置与 ASR / LLM 分离。
- 真实 Provider 缺少 API Key、Base URL 或 Model 时启动失败，不静默使用 Mock。

### Retrieval Planner

- `AGENT_PLANNER_PROVIDER=mock|llm`。
- `llm` 要求 `LLM_PROVIDER=openai` 及完整 LLM 配置；可用 `AGENT_PLANNER_MODEL` 单独覆盖模型。
- Provider 认证、配置或明确拒绝不会伪装成成功；只有允许的 Planner transient failure 或 invalid plan 才进入 Basic QA fallback。Tool 已执行后若 Synthesizer 失败，错误会直接向上返回，不会重新执行 Basic QA。

自动化测试默认使用 Mock Provider，不发起付费请求。真实 AI 测试必须显式开启对应环境门控，并从本地环境变量读取密钥。

## 8. M7 Reliability 最终实现

### Transactional Outbox

`AnalysisCommandService.start()` 是外层 `@Transactional` 边界：`analysis_task` 与初始 `analysis_outbox_event` 在同一 MySQL 本地事务中插入；任意一条失败，两者一起回滚。Retry 状态迁移与 retry outbox event 也在一个本地事务中提交。

`OutboxPublisher` 定时扫描到期的 `PENDING` 事件并发送 RocketMQ。事件生命周期：

```text
PENDING → PUBLISHED | EXHAUSTED | CANCELLED | INVALID
```

发布是轮询和最终一致的。发送成功后、标记 `PUBLISHED` 前崩溃仍可能重复发布，因此 Consumer 正确性不能依赖消息只来一次。

### Task 状态机与原子 Claim

```text
PENDING ──claim──> PROCESSING ──success──> SUCCESS
   │                  │
   │                  ├── retryable + budget → RETRY_WAITING
   │                  ├── retryable + exhausted → FAILED
   │                  └── non-retryable → FAILED
   └── dispatch exhausted / invalid → FAILED

RETRY_WAITING ──retry_not_before due + claim──> PROCESSING
stale PROCESSING ──recovery──> RETRY_WAITING | FAILED
```

- Claim 使用单条 MySQL 条件 UPDATE；只有受影响行数为 1 的 Worker 获得处理资格。
- `SUCCESS` / `FAILED` 是不可逆终态，不能被 Claim、Recovery 或旧失败路径覆盖。
- `status` 表示生命周期；`stage` 表示可观察的流水线进度，Resume 正确性不依赖 `stage` 字符串。

### Retry、Resume、Lease 与 Fencing

- Retry 使用允许列表区分 transient failure 与不可重试错误，受 `ANALYSIS_MAX_ATTEMPTS` 限制。
- `retry_not_before` 保存退避时间；旧的重复消息不能绕过 backoff。
- Transcript 已持久化时跳过 MinIO / FFmpeg / ASR；Summary 已持久化时跳过 LLM 与结果重写，实现 Durable Resume。
- Worker 在处理期间以 `processing_generation` 作为 Fencing Token，并按固定间隔更新 heartbeat。
- 超过 Processing Lease 的 Worker 可由 Recovery 接管；接管会推进 generation。
- 旧 Worker 恢复后，所有带旧 generation 的进度、重试、成功和失败 UPDATE 都得到 0 affected rows，并停止触碰任务生命周期。
- Redis / SSE 更新发生在持久状态之外；通知失败不能回滚 MySQL 终态。

### M7 明确边界

- RocketMQ 是 at-least-once，不是 Exactly Once；系统通过 Atomic Claim、Generation Fencing、状态机条件更新和幂等持久化，避免重复消息导致任务业务状态被重复处理或旧 Worker 覆盖。
- Transactional Outbox 不是 MySQL + RocketMQ 分布式事务；它提供本地原子写入和最终可靠投递。
- 第三方调用超时不能证明 Provider 没有实际执行，Retry 仍可能产生重复计费或外部副作用。
- Crash Recovery 默认需要等待 Lease；Outbox 轮询会引入发布延迟。
- 没有 DLQ 管理界面、手动重试 UI 或跨服务分布式追踪。

## 9. M8.1 Adaptive RAG 最终实现

上下文策略依据 Transcript 字符数，而不是视频时长：

```text
transcriptChars <= RAG_DIRECT_CONTEXT_MAX_CHARS (default 8000)
  → DIRECT_CONTEXT / NOT_REQUIRED
  → 全量 Transcript Segments 直接进入 QA Context
  → 不调用 Embedding 或 Qdrant

transcriptChars > threshold
  → RAG / NOT_BUILT
  → build: Chunk → batch Embedding → Qdrant
  → query: Question Embedding → Qdrant payload filter
           (userId=currentUserId AND videoId=当前视频) → Top-K (default 5)
  → retrieved Chunks 进入 QA Context
```

- `video_rag_index` 生命周期为 `NOT_REQUIRED / NOT_BUILT / BUILDING / READY / FAILED`，与 Analysis 状态机分离。
- Index build 当前是同步请求；BUILDING Claim 使用短 MySQL 事务先提交，防止并发重复构建。
- Rebuild 严格删除旧 vectors 后再 upsert deterministic point IDs；删除失败则构建失败，不把混合索引标记 READY。
- RAG 查询和 Agentic `SEARCH_TRANSCRIPT` 在执行时重新 `requireReady()`，不只相信规划前快照。
- DIRECT citation 只接受本次提供的 Segment Index；RAG citation 只接受本次 Top-K 返回的 Chunk Index。
- Citation 的文本和时间戳来自 MySQL / Qdrant metadata，不采用 LLM 自报 timestamp。
- 隔离不是笼统的“相似度过滤”：服务层先通过 MySQL ownership 校验当前用户是否拥有视频，向量检索再用 Qdrant payload filter 同时约束 `userId=currentUserId` 与 `videoId=当前视频`。
- MySQL 保存 Transcript 和索引生命周期，是事实来源；Qdrant 保存可重建的派生向量。两者没有原子事务，通过失败状态和 rebuild 收敛。
- 当前业务键限制同一 `videoId + analysisType + modelVersion` 只能创建一个 AnalysisTask；这也是当前 Transcript / RAG versioning 的业务边界，不应声称支持任意重新分析版本。

## 10. M8.2 Agentic Retrieval 最终实现

### 有界执行模型

```text
Question
  → Planner 只看问题和后端提供的 metadata
  → Validator 校验封闭 Tool、参数、动作数量
  → Executor 在 server-bound AgenticQaContext 中执行
  → Evidence 去重并限制数量 / 字符数
  → Synthesizer 仅基于 Evidence 回答
  → Backend 将 Evidence IDs 映射为真实 Citation
```

默认限制：最多 4 个 Tool Call、12 个 Evidence Item、12000 Evidence Chars。配置还有硬上限并在非法时 fail-fast，不能用异常配置无限放大执行成本。

### 三个封闭 Tool

| Tool | 用途 | 数据路径 |
| --- | --- | --- |
| `GET_VIDEO_SUMMARY` | “这个视频主要讲了什么？” | 读取 MySQL 已持久化 Summary / Chapters / Key Points；不重新调用 Summary Provider；无伪造时间戳 |
| `GET_TRANSCRIPT_BY_TIME` | “第 10 分钟讲了什么？” | 以 `analysisTaskId + videoId + time range` 在 MySQL 做区间查询；不调用 Embedding / Qdrant |
| `SEARCH_TRANSCRIPT` | 语义问题或比较问题 | DIRECT_CONTEXT 返回完整短 Transcript；RAG 模式执行 Embedding + Qdrant Top-K，可进行有界多查询分解 |

Plan schema 只接受封闭 Tool enum，不允许任意 Tool 名称，也不包含 `userId` 或 `videoId`。多 Action 只允许受控的 `SEARCH_TRANSCRIPT` 组合；最终 strategy 由后端根据已验证 Action 推导，不信任 Planner 自报的 intent / label。

### Evidence、Citation 与审计关闭结果

- Evidence 使用请求内 `E1 / E2 / ...` ID，去重后再按数量和字符数限制。
- Synthesizer 输入使用结构化 JSON；Transcript / Summary 文本作为 Evidence 字段中的不可信数据，而不是可闭合的 Prompt delimiter。
- LLM 返回的未知、空白、大小写不匹配或已被限制移除的 Evidence ID 会被丢弃。
- 最终 Citation 只从后端实际 Evidence 构建；LLM response schema 不提供可信 timestamp 字段。
- RAG Tool 执行前重新检查 READY；RAG rebuild 的 BUILDING 状态先提交且旧向量删除失败会终止构建。
- Time Tool 使用数据库区间查询；RAG Tool 不再无用地加载完整长 Transcript，降低有界成本放大。
- Planner / Answer Provider 对认证错误、非法配置、429/5xx/timeout 和内部错误做区分；配置错误不会静默降级 Mock。
- 只有允许的 Planner transient failure（代码映射为 `AGENT_PLANNER_FAILED`）或 invalid plan（`INVALID_REQUEST`）可以回退 M8.1 Basic QA。
- Tool 执行完成后若 Synthesizer / Agentic Answer Provider 失败，不会回退并重新执行 Basic QA；该失败直接向上返回。Provider 认证、配置或明确拒绝同样不会被 fallback 隐藏。

## 11. 当前已知限制与 Explicit Non-claims

### 架构与运行限制

- 模块化单体，不是微服务；未证明百万 QPS、高可用集群或生产级多实例部署。
- Docker Compose 使用单 MySQL、单 Redis、单 MinIO、单 RocketMQ Broker、单 Qdrant；没有基础设施 HA。
- SSE subscriptions 保存在单应用实例内存，无 Redis Pub/Sub；多实例广播和粘性路由未实现。
- MinIO / Qdrant 删除发生在 MySQL 删除提交后，属于 best-effort cleanup，失败可能留下不可达 orphan data。
- JWT 没有 refresh / revoke；logout 只清除前端状态，已签发 Token 在过期前仍可验证。

### Reliability 非声明

- 不声称 Exactly Once MQ。
- 不声称 MySQL + RocketMQ 分布式事务或跨系统强一致。
- 不声称无限重试或零重复外部调用。
- 不使用 Seata、XA、2PC、Redis 分布式锁、工作流引擎、Kafka、Kubernetes 或微服务治理。

### RAG / Agent 非声明

- MySQL + Qdrant 不是强一致；Qdrant 是 derived index。
- Basic RAG 是 dense embedding + single-query Top-K；没有 BM25、Hybrid Search、Reranker、Cross-Encoder、Self-RAG 或 CRAG，也没有 `RAG_MIN_SCORE`。
- RAG index build 是同步的；非常长的 Transcript 可能增加请求时间和成本。
- 检索可能受 Chunk 边界、Embedding 质量和 Top-K 影响，不保证每次召回最佳证据。
- Agentic Retrieval 是单轮、有界、面向视频证据的 Tool Router，不是通用 Autonomous Agent、Multi-Agent、MCP、GraphRAG、Memory Agent 或 exactly-correct intent classifier。
- Planner 可能误路由；Evidence ID 验证证明引用来自允许证据，但不能数学证明回答每一句都被证据语义蕴含。
- Prompt Injection 防护依赖 server-bound identity、封闭 Tool、参数校验、结构化 Evidence 和后端 Citation Mapping；这限制权限提升与伪造引用，但不声称完全解决模型层 Prompt Injection 或内容操控。
- 没有 Web Search、外部知识 Tool、长期记忆、反思、自我修正或循环规划。

### AI Provider 非声明

- 未自研 FFmpeg、ASR、Embedding 或 LLM 模型。
- Provider 的可用性、限流、账单、模型行为和网络故障属于外部边界。
- Structured Output、Prompt 和 Backend Validation 降低风险，但不保证 LLM 永不幻觉或格式永远正确。

## 12. 配置、秘密与验证边界

- `.env` 不得提交；`.env.example` 只保留占位值。
- JWT Secret、数据库密码、ASR / LLM / Embedding API Key 不得写入源码、测试、日志、报告或 handoff。
- Docker Compose 只启动基础设施；后端和前端分别在 Host 启动。
- Flyway V1–V8 是已验收历史 migration，不得修改；任何未来 schema 变化只能新增 migration，但项目当前已 frozen。
- 默认 Mock 测试不得调用付费 Provider；真实 AI 验收必须显式开启环境门控。

## 13. 新会话接手规则

新会话默认进入 **Learning Mode**，不是 Implementation Mode：

1. 以当前 `main@v1.0.0` 源码为最高事实来源；
2. 不继续增加功能，不进入 M8.3 / M9，不引入新技术栈；
3. 不为“优化”擅自修改架构；
4. 未经用户明确要求，不修改任何 Java、Vue、SQL、Docker、配置或文档；
5. 不自动执行 `git add`、`git commit`、`git reset`、`git clean`、`git push`；
6. 不修改数据库，不删除 Docker Volume，不运行付费 AI 请求；
7. 发现 Bug 时先说明问题、位置、原因、影响和边界，不自动修复；
8. 后续主要任务是帮助用户启动和排查项目、理解真实源码、解释架构取舍并准备 Java Backend / AI Application 面试。

建议阅读顺序：本文档 → 当前源码与 migrations → M7 / M8.1 / M8.2 Acceptance Report → 最终 M8.2 Audit 与关闭提交。历史报告中的时间性表述不得覆盖最终源码事实。
