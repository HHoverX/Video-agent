# VideoAgent 实施计划

## 1. 仓库检查结论

检查日期：2026-08-08

当前目录结构：

```text
Video agent/
├── .claude/
│   └── settings.local.json
└── VideoAgent_Codex_Spec.md
```

结论：

- 当前目录不是 Git 仓库，未发现 `.git`。
- 未发现 `AGENTS.md` 或其他项目级编码约束。
- 除 Codex 规格与本机权限配置外，没有后端、前端、基础设施或测试代码，因此按全新项目处理。
- 本机 Maven 3.9.12 使用 JDK 23，可通过 `maven.compiler.release=21` 构建 Java 21 目标；命令行 `java` 默认仍为 Java 10，直接运行后端前需要切换到 Java 21+。Node.js 22.22.3、npm 10.9.8、Docker 29.5.2、Docker Compose 5.1.3 已安装。
- `.claude/settings.local.json` 属于用户本机配置，不纳入项目实现，也不修改。

## 2. 实施边界与约定

- 严格按 Milestone 1 → 8 顺序实施；每完成一个 Milestone，执行相应构建/测试/验收后停止，等待下一步指令。
- Milestone 1~7 构成稳定 V1；Milestone 8 是 V1.5，只有 V1 验收稳定后才开始。
- 保持单体模块化后端，不拆微服务；不引入规格明确排除的 Kafka、Kubernetes、Elasticsearch、多 Agent、OCR、VLM 等能力。
- MySQL 是持久业务事实源；Redis 仅承担实时进度和短期去重；RocketMQ 消息只携带 `taskId`、`videoId`。
- ASR 与 LLM 都通过 Provider 接口隔离，默认 Mock；真实密钥只通过环境变量注入。
- 数据库变更统一使用 Flyway；DTO 与 Entity 分离；Controller 保持薄；核心流程必须可测试。
- 文件清单是当前预期，允许在对应 Milestone 内因编译或测试需要增加同职责文件，但不得提前实现后续 Milestone 业务。

## 3. Milestone 计划

### Milestone 1 — 项目骨架

目标：

- 创建 Java 21 / Spring Boot 3.x Maven 后端骨架。
- 创建 Vue 3 + TypeScript + Vite 前端骨架，接入 Pinia、Vue Router、Axios、Element Plus。
- 提供 MySQL、Redis、MinIO、RocketMQ NameServer/Broker 的 Docker Compose。
- 提供环境变量模板和本地开发配置。
- 实现 `GET /api/health` 与最小前端健康页。

新增/修改文件：

```text
.env.example
.gitignore
docker-compose.yml
README.md
infra/rocketmq/broker.conf
backend/pom.xml
backend/src/main/java/com/videoagent/VideoAgentApplication.java
backend/src/main/java/com/videoagent/common/health/HealthController.java
backend/src/main/java/com/videoagent/common/health/HealthResponse.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/common/health/HealthControllerTest.java
frontend/package.json
frontend/package-lock.json
frontend/index.html
frontend/tsconfig*.json
frontend/vite.config.ts
frontend/src/main.ts
frontend/src/App.vue
frontend/src/router/index.ts
frontend/src/services/api.ts
frontend/src/views/HomeView.vue
frontend/src/styles/main.css
```

验收标准：

- `docker compose config` 成功。
- `docker compose up -d` 后 5 个基础设施服务启动并通过可用性检查。
- `mvn test` 与 `mvn package` 成功。
- `npm run build` 成功。
- 后端启动后 `GET /api/health` 返回 HTTP 200 和明确的成功状态。
- 前端可启动并展示 VideoAgent 基础页，能请求后端健康接口。

风险：

- 本机默认 `java` 是 Java 10，需使用 Maven 当前的 JDK 23 或显式配置 Java 21+ 运行时。
- Docker 首次拉取 MySQL、Redis、MinIO、RocketMQ 镜像耗时较长，且受网络和 Docker Desktop 状态影响。
- RocketMQ Broker 在 Windows Docker Desktop 下的监听/广播地址需实机验证。

### Milestone 2 — 视频上传

目标：

- 实现普通 multipart MP4 上传链路：前端 → Spring Boot → MinIO → MySQL。
- 使用 Flyway 创建 `video` 表及索引。
- 实现视频列表与详情所需的后端基础能力；前端完成上传、列表与最小详情页。
- 校验格式、大小和必要字段，存储失败时避免产生有效数据库记录。

新增/修改文件：

```text
backend/src/main/resources/db/migration/V1__create_video_table.sql
backend/src/main/java/com/videoagent/video/{controller,service,repository,entity,dto}/...
backend/src/main/java/com/videoagent/storage/{ObjectStorageService,MinioStorageService}.java
backend/src/main/java/com/videoagent/common/{exception,response}/...
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/video/...
frontend/src/views/{UploadView,VideoListView,VideoDetailView}.vue
frontend/src/services/video.ts
frontend/src/types/video.ts
frontend/src/router/index.ts
README.md
```

验收标准：

- 上传 MP4 返回 `videoId`，MinIO 指定 bucket 出现对象，MySQL `video` 表出现对应记录。
- 视频列表可查看刚上传的视频，详情接口返回 DTO 而非 Entity。
- 非法格式/超限文件返回统一错误码；核心上传 Service 测试通过。
- 后端、前端构建及相关测试通过。

风险：

- MinIO 写成功而数据库写失败存在跨资源一致性风险；V1 使用补偿删除并记录其边界。
- 普通 multipart 会占用应用带宽和临时资源；分片、断点续传明确留到后续增强。
- 视频时长提取尚未接入 FFmpeg时可暂为空，不能提前耦合 Milestone 4。

### Milestone 3 — 异步分析框架

状态：**已完成并通过真实 MySQL、Redis、RocketMQ 与浏览器验收。**

目标：

- 使用 Flyway 创建 `analysis_task` 表和业务唯一约束。
- 实现分析任务创建、短期重复提交防护、RocketMQ Producer/Consumer。
- Consumer 用确定性的模拟步骤更新 20/40/70/90/100 进度，不接 ASR/LLM。
- Redis 保存带 TTL 的实时进度，MySQL 保存最终任务状态。

新增/修改文件：

```text
backend/src/main/resources/db/migration/V2__create_analysis_task_table.sql
backend/src/main/java/com/videoagent/analysis/{controller,service,producer,consumer,repository,entity,dto}/...
backend/src/main/java/com/videoagent/analysis/progress/{AnalysisProgressStore,RedisAnalysisProgressStore}.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/analysis/...
frontend/src/services/analysis.ts
frontend/src/types/analysis.ts
frontend/src/views/VideoDetailView.vue
README.md
```

验收标准：

- `POST /api/videos/{videoId}/analysis` 快速返回 `taskId` 和 `PENDING`。
- Consumer 后台处理，Redis 进度按模拟阶段变化并设置 TTL，MySQL 最终为 `SUCCESS`。
- `GET /api/analysis/{taskId}` 在 Redis 缺失时可回退 MySQL 最终状态。
- 重复请求不会生成两个同时有效的相同分析任务。

风险：

- DB 写成功、MQ 发送失败是 V1 已知一致性风险，本阶段记录但不提前实现 Transactional Outbox。
- Redis 不能作为唯一幂等依据；唯一约束与任务状态转换需处理并发竞争。
- MQ 重投可能造成重复消费；本阶段已通过原子 PENDING 抢占和 SUCCESS 短路保证框架级幂等，更复杂的失败重试策略留到后续阶段。

### Milestone 4 — FFmpeg + Mock ASR

状态：**已完成并通过真实 MySQL、Redis、MinIO、RocketMQ、FFmpeg 与浏览器验收。**

目标：

- 创建带时间戳的 transcript segment 表。
- 从 MinIO 下载视频到受控临时目录，FFmpeg 确定性提取音频并清理临时文件。
- 定义 `AsrProvider`，实现默认 `MockAsrProvider`，将 segment 按序入库。
- 将任务阶段扩展为提取音频、语音识别、保存；媒体处理失败时任务进入 `FAILED`。

新增/修改文件：

```text
backend/src/main/resources/db/migration/V3__create_video_transcript_segment_table.sql
backend/src/main/java/com/videoagent/media/{MediaProcessor,FfmpegMediaProcessor,...}.java
backend/src/main/java/com/videoagent/asr/{AsrProvider,MockAsrProvider,...}.java
backend/src/main/java/com/videoagent/transcript/{controller,service,repository,entity,dto}/...
backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java
backend/src/main/java/com/videoagent/storage/{ObjectStorageService,MinioStorageService}.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/{media,asr,transcript,analysis}/...
frontend/src/services/transcript.ts
frontend/src/types/transcript.ts
frontend/src/views/VideoDetailView.vue
README.md
MILESTONE_4_ACCEPTANCE_REPORT.md
```

验收标准：

- 分析 MP4 后 transcript segments 正确入库，含递增 `segment_index`、`start_ms`、`end_ms`。
- `GET /api/videos/{videoId}/transcript` 按时间顺序返回结果。
- FFmpeg 非零退出、超时或文件异常会记录错误并把任务置为 `FAILED`。
- Mock ASR 在没有 API Key 时可重复、稳定地完成本地演示和测试。

风险：

- 本机/部署环境可能未安装 FFmpeg；需提供明确配置与启动前诊断。
- 进程超时、stderr 读取和临时文件清理不当可能造成资源泄漏或死锁。
- 禁止拼接用户输入为 shell 命令，文件路径必须作为受控参数传递。

### Milestone 5 — LLM 摘要

状态：**已完成并通过真实 MySQL、Redis、MinIO、RocketMQ、FFmpeg、Mock Summary 与浏览器验收。**

目标：

- 创建 summary、chapter、key point 表。
- 定义 `VideoSummaryProvider` 及结构化请求/响应，实现确定性 Mock 与 LangChain4j OpenAI-compatible Provider。
- 将 transcript 转换为 overview、chapters、key points 并事务性保存。
- 前端展示摘要/章节/关键点，点击时间戳跳转播放器。

新增/修改文件：

```text
backend/src/main/resources/db/migration/V4__create_video_summary_tables.sql
backend/src/main/java/com/videoagent/summary/provider/...
backend/src/main/java/com/videoagent/summary/{controller,service,repository,entity,dto}/...
backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/{summary,analysis}/...
frontend/src/services/summary.ts
frontend/src/types/summary.ts
frontend/src/views/VideoDetailView.vue
README.md
MILESTONE_5_ACCEPTANCE_REPORT.md
```

验收标准：

- 一次分析生成且持久化 overview、chapters、key points。
- 结果接口按顺序返回结构化 DTO，前端完整展示。
- 当前尚无播放器，chapter、key point 与 transcript 时间戳按规格仅展示，不提前实现视频代理或播放器架构。
- 未配置真实 LLM Key 时默认 Mock 仍可完成完整链路。

风险：

- 真实模型结构化输出可能不符合 schema，必须做校验并映射为清晰错误。
- transcript 可能超过上下文限制；V1 采用明确长度保护，不提前实现 RAG。
- 摘要多表写入需要事务边界，避免部分成功。

### Milestone 6 — SSE 实时进度

状态：**已完成并通过真实 MySQL、Redis、MinIO、RocketMQ、FFmpeg、SSE 与浏览器验收。**

目标：

- 实现 `GET /api/analysis/{taskId}/events`，从 Redis 进度向客户端发送 SSE。
- 保留 `GET /api/analysis/{taskId}` 轮询兜底。
- 前端显示排队、提取音频、语音识别、AI 总结、完成/失败状态，并正确重连或降级。

新增/修改文件：

```text
backend/src/main/java/com/videoagent/analysis/controller/AnalysisEventController.java
backend/src/main/java/com/videoagent/analysis/dto/AnalysisProgressEventResponse.java
backend/src/main/java/com/videoagent/analysis/event/AnalysisEventBroadcaster.java
backend/src/main/java/com/videoagent/analysis/service/{AnalysisEventService,AnalysisEventProperties,AnalysisProgressUpdateService}.java
backend/src/test/java/com/videoagent/analysis/{event,service,controller}/...
backend/src/test/java/com/videoagent/analysis/AnalysisSseInfrastructureIntegrationTest.java
frontend/src/composables/useAnalysisEvents.ts
frontend/src/services/analysis.ts
frontend/src/types/analysis.ts
frontend/src/views/VideoDetailView.vue
README.md
MILESTONE_6_ACCEPTANCE_REPORT.md
```

验收标准：

- SSE 按业务阶段推送进度，任务完成或失败后正常关闭。
- 浏览器断开 SSE 不影响后台任务；重连后能得到当前状态。
- Redis 不可用或 SSE 失败时，前端可退化为低频 REST 查询。
- 不遗留无限增长的 emitter、线程或定时任务。

风险：

- 代理超时、浏览器连接上限与心跳策略会影响长连接稳定性。
- 每连接一个轮询线程会降低扩展性；V1 需采用有界、可清理的实现。
- Redis 故障降级不得改变 MySQL 任务事实状态。

### Milestone 7 — 可靠性与 V1 收口

目标：

- 完成 Consumer 业务幂等、PROCESSING 并发保护与重复分析保护。
- 区分可重试/不可重试错误，配置第三方调用超时和有限重试。
- 完善统一错误码、带 `videoId/taskId/stage` 的上下文日志和敏感信息保护。
- 补齐规格要求的单元/集成测试与 README Architecture Decisions。

新增/修改文件：

```text
backend/src/main/java/com/videoagent/analysis/{consumer,service}/...
backend/src/main/java/com/videoagent/common/{exception,retry,logging}/...
backend/src/main/java/com/videoagent/ai/{asr,llm}/...
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/analysis/...
backend/src/test/java/com/videoagent/integration/...
README.md
```

验收标准：

- 重复消费 `SUCCESS` 任务直接跳过；同一任务不会被两个 Consumer 并发执行业务流程。
- 重复提交不产生重复有效任务；失败任务保存 `error_code/error_message/retry_count`。
- 仅网络超时、429、5xx 等瞬时错误有限重试，达到上限后进入 `FAILED`。
- 覆盖 AnalysisService、Redis progress、Mock ASR/LLM、状态转换及规格列出的集成场景。
- README 包含启动方式、环境变量、Mock 模式、架构图、已知限制与 Redis/RocketMQ/ASR 的 Architecture Decisions。

风险：

- 幂等状态更新必须使用条件更新/事务，单纯“先查后改”仍有竞态。
- RocketMQ 自身重试与应用层重试叠加可能放大请求，需要明确单层次数和失败语义。
- Testcontainers 若在当前环境成本过高，可 Mock 外部系统，但必须保留核心业务集成测试。

### Milestone 8 — Video RAG（V1.5，门控阶段）

目标：

- 仅在 Milestone 1~7 稳定后，实现 transcript 分块、embedding、向量检索与基于证据的问答。
- 回答只能基于当前视频 transcript，返回引用片段和相关 timestamp。
- 评估 PostgreSQL + pgvector 的独立引入方式，不为 RAG 直接大规模迁移现有 MySQL 业务库。

新增/修改文件：

```text
（方案评审后确定）docker-compose.yml
（方案评审后确定）backend/pom.xml
backend/src/main/resources/db/migration/...conversation_and_chat...
backend/src/main/java/com/videoagent/chat/{controller,service,repository,entity,dto}/...
backend/src/main/java/com/videoagent/rag/{chunking,embedding,retrieval}/...
backend/src/test/java/com/videoagent/{chat,rag}/...
frontend/src/components/VideoChat.vue
frontend/src/services/chat.ts
frontend/src/types/chat.ts
frontend/src/views/VideoDetailView.vue
README.md
```

验收标准：

- 长 transcript 被分块并建立向量索引，问答时不把全文直接发送给 LLM。
- 回答只引用当前 `videoId` 的检索结果，并返回可跳转的 timestamp。
- 无相关证据时明确说明无法从视频内容回答，不生成无依据答案。
- RAG/向量库不可用不影响 V1 的上传、分析和结果查看链路。

风险：

- MySQL 与向量数据库形成双存储后存在索引同步和重建问题。
- chunk 大小、重叠、召回数量与 embedding 模型版本会显著影响效果，需要可追踪版本。
- 这是独立 V1.5 能力，开始前必须先做存储方案评审，不能借机迁移整个业务数据库。

## 4. 每个 Milestone 的固定停止点

每个 Milestone 完成时只做以下收口，不自动进入下一阶段：

1. 运行相关编译与测试。
2. 按该 Milestone 验收标准做可执行检查；环境限制导致无法执行的项目须明确标注。
3. 汇报新增/修改文件、已完成内容、测试结果、未完成项和已知风险。
4. 停止开发，等待用户明确要求继续下一个 Milestone。
