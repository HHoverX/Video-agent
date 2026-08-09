# Milestone 5 验收报告

验收日期：2026-08-09（Asia/Shanghai）  
结论：**PASS**

## 1. 范围结论

Milestone 5 已完成以下闭环：

```text
MinIO Video
→ FFmpeg
→ Mock ASR
→ Timestamp Transcript
→ VideoSummaryProvider
→ Mock / LangChain4j Structured Output
→ MySQL
→ REST API
→ Vue VideoDetailView
```

本次没有实现 Milestone 6，也没有加入 SSE、WebSocket、RAG、Embedding、Tool Calling、Agent Loop、Multi-Agent、OCR、VLM、Transactional Outbox、DLQ 扩展、分片上传、Kubernetes或微服务。

## 2. Git 检查点

- `.env` 由 `.gitignore` 排除，`git ls-files .env` 无输出。
- Milestone 4 独立提交：`b9a0eaf feat: complete milestone 4 media transcription`。
- AI handoff 独立提交：`1fe5567 docs: add AI agent handoff guide`。
- 两个提交的顺序和文件边界符合要求；M5 工作尚未提交，等待后续明确指令。

## 3. 数据库设计与实际迁移

Flyway migration：`V4__create_video_summary_tables.sql`，已在真实 MySQL 8.4 上应用，`flyway_schema_history` 的 V1–V4 均为 `success=1`。

### `video_summary`

- 字段：`id`、`video_id`、`task_id`、`overview`、`created_at`、`updated_at`。
- `UNIQUE(task_id)`：一个 M5 task 只能有一份 overview。
- `INDEX(video_id, created_at)`：支持按视频读取最新结果。
- `video_id`、`task_id` 外键保证结果归属；task 删除时级联清理结果。

### `video_chapter`

- 字段：`id`、`video_id`、`task_id`、`chapter_index`、`title`、`summary`、`start_ms`、`end_ms`。
- `UNIQUE(task_id, chapter_index)`：同一 task 的章节顺序唯一。
- `INDEX(video_id, chapter_index)`、`INDEX(video_id, start_ms)`：支持顺序读取和时间定位。
- `CHECK(end_ms > start_ms)` 及非空文本约束阻止无效结构入库。

### `video_key_point`

- 字段：`id`、`video_id`、`task_id`、`point_index`、`content`、`start_ms`、`end_ms`。
- `UNIQUE(task_id, point_index)`：同一 task 的关键点顺序唯一。
- `INDEX(video_id, point_index)`、`INDEX(video_id, start_ms)`：支持顺序读取和时间定位。
- 时间范围及非空文本约束与 chapter 一致。

Chapters 与 Key Points 均使用可查询的规范化行存储，没有整体塞入 JSON/LONGTEXT。

## 4. Provider 与 LangChain4j 边界

- `VideoSummaryProvider` 只接收 `VideoSummaryRequest(videoId, taskId, transcriptSegments)`，返回 `VideoSummaryResult(overview, chapters, keyPoints)`。
- `MockVideoSummaryProvider` 返回确定性、带时间戳结果，不需要 API Key，默认本地和自动化验收使用它。
- `LangChain4jVideoSummaryProvider` 只负责 Prompt、AI Service 调用、异常映射和结构校验；业务 Service 与 Consumer 不散落厂商 SDK。
- 真实模式使用 LangChain4j AI Services + OpenAI-compatible `ChatModel`，启用 `RESPONSE_FORMAT_JSON_SCHEMA` 和 `strictJsonSchema(true)`。
- 配置项：`LLM_PROVIDER`、`LLM_API_KEY`、`LLM_MODEL`、可选 `LLM_BASE_URL`、`LLM_TIMEOUT`、`LLM_MAX_RETRIES`。
- `LLM_MAX_RETRIES` 只允许 0–3；timeout 必须为正数；未配置 provider 时使用 Mock，选择 `openai` 但 Key/Model 缺失时安全回退 Mock，未知 provider 启动失败。
- 未启用请求/响应日志，异常对外映射为固定消息，不记录或返回 API Key/远端敏感响应。

实现遵循 LangChain4j 官方 Structured Output 与 OpenAI integration 文档：

- <https://docs.langchain4j.dev/tutorials/structured-outputs/>
- <https://docs.langchain4j.dev/integrations/language-models/open-ai/>

## 5. Structured Result validation

入库前执行两层防御：Provider 返回后校验一次，`VideoSummaryService` 事务写入前再次校验。

- transcript 必须非空。
- overview、chapter title/summary、key point content 必须非空并满足长度上限。
- chapter 数量 1–50；key point 数量 1–100。
- 每个 `startMs/endMs` 必须完全位于 transcript 最小/最大时间范围内，且 `endMs > startMs`。
- 越界结果拒绝并映射 `LLM_SUMMARY_INVALID`，不静默篡改模型结果。
- Chapters 与 Key Points 按 `startMs/endMs` 规范化排序，再生成连续 index。
- summary、chapters、key points 在事务中替换，任一写入失败不会留下部分成功结果。

## 6. Analysis 版本与 Consumer 流程

- M3 保持：`FRAMEWORK / m3-simulation-v1`。
- M4 保持：`TRANSCRIPTION / m4-ffmpeg-mock-asr-v1`。
- M5 新增：`STRUCTURED_SUMMARY / m5-langchain4j-structured-v1`。

Consumer 实际阶段：

```text
PREPARING 10
→ EXTRACTING_AUDIO 35
→ TRANSCRIBING 70
→ SAVING_TRANSCRIPT 75
→ SUMMARIZING 85
→ SAVING 95
→ DONE / SUCCESS 100
```

M3/M4 历史 migration 和历史任务未修改。Consumer 对 `SUCCESS` 重复消息直接跳过；M3/M4/M5 基础设施测试使用每次运行唯一的 RocketMQ consumer group，避免不同版本测试 Consumer 相互负载均衡消息。

## 7. REST API

- `GET /api/videos/{videoId}/summary`：返回最新成功 M5 task 的 overview；视频存在但尚无结果时返回 HTTP 204。
- `GET /api/videos/{videoId}/chapters`：返回最新成功 M5 task 的有序章节数组。
- `GET /api/videos/{videoId}/key-points`：返回最新成功 M5 task 的有序关键点数组。
- 所有接口返回 DTO，不直接暴露 Entity；不存在的视频沿用统一 `VIDEO_NOT_FOUND`。

## 8. 自动化测试结果

最终全量命令启用了所有真实基础设施测试开关：

```powershell
$env:VIDEOAGENT_INFRA_TEST='true'
$env:VIDEOAGENT_M2_INFRA_TEST='true'
$env:VIDEOAGENT_M3_INFRA_TEST='true'
$env:VIDEOAGENT_M4_INFRA_TEST='true'
$env:VIDEOAGENT_M5_INFRA_TEST='true'
$env:VIDEOAGENT_FFMPEG_TEST='true'
$env:FFMPEG_PATH='...\ffmpeg.exe'
mvn test
```

结果：**58 tests / 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**。

覆盖内容：

- Mock Summary 确定性与 timestamp segments。
- Mock/Real Provider 配置切换、缺失配置回退、timeout/retry 配置约束。
- LangChain4j AI Service 失败映射为 `LLM_SUMMARY_FAILED`，敏感远端信息不泄露。
- Structured Result 文本、数量、排序与 timestamp 越界拒绝。
- summary、chapters、key points 正确持久化及顺序。
- 三个 REST API、有结果/无结果/视频不存在行为。
- Consumer 全流程阶段、Provider 失败后 task FAILED、SUCCESS 重复消费跳过。
- M2 上传、M3 异步框架、M4 FFmpeg/ASR 全部真实回归。
- Redis 丢失后从 MySQL 回退。
- FFmpeg 成功、非零 exit、超时及临时媒体文件清理。

另外执行：

```powershell
mvn -DskipTests package
npm run build
```

结果：后端可执行 JAR 生成成功；前端 TypeScript 检查和 Vite production build 成功。

## 9. 真实基础设施与浏览器 E2E

Docker 服务状态：MySQL、Redis、MinIO、RocketMQ NameServer、RocketMQ Broker 均为 `running / healthy`。

### 自动化真实链路

`StructuredSummaryInfrastructureIntegrationTest` 实际执行：

```text
生成 6 秒 MP4
→ multipart 上传
→ MinIO + video 元数据
→ POST analysis 快速返回 PENDING taskId
→ RocketMQ Consumer
→ MinIO 下载
→ FFmpeg 提取 WAV
→ Mock ASR 三个字幕片段
→ Mock Summary
→ 三张结果表
→ SUCCESS
→ transcript/summary/chapters/key-points API
→ SUCCESS 重复消费跳过
→ 删除 Redis key
→ MySQL 状态回退
→ 临时文件清理
```

同一测试还插入并验证 M3、M4 历史任务仍保留原 analysis type/model version。

### 手工浏览器链路

- 上传测试视频：`videoId=33`，标题 `M5 Browser Acceptance`，大小 64,368 bytes。
- MinIO 对象：`videos/2026/08/08/700bbafe-b0b8-414c-a70a-beabf0a45a01.mp4`，`mc stat` 显示 63 KiB、`Content-Type: video/mp4`。
- 浏览器详情页点击“开始 AI 分析”，先观察到 `task #30 / 排队中 / 0%`。
- 普通轮询后观察到 `已完成 / 分析完成 / 100%`。
- 页面显示：1 个 Overview、2 个 Chapters、3 个 Key Points、3 个 transcript segments。
- 时间范围显示为：Chapters `00:00–00:04`、`00:04–00:06`；Key Points `00:00–00:02`、`00:02–00:04`、`00:04–00:06`。
- 当前无播放器，按规格只展示时间戳，没有新增视频代理或复杂播放器。

MySQL 直接核验：

```text
analysis_task: taskId=30, videoId=33,
  STRUCTURED_SUMMARY / m5-langchain4j-structured-v1,
  SUCCESS / DONE / 100
video_transcript_segment: 3 rows
video_summary: 1 row
video_chapter: 2 rows, indexes 0/1
video_key_point: 3 rows, indexes 0/1/2
```

Redis 最终快照曾为：

```json
{"status":"SUCCESS","stage":"DONE","progress":100,"message":"分析完成"}
```

TTL 为 86,175 秒。随后按验收要求删除该可重建缓存，`GET /api/analysis/30` 仍从 MySQL 返回完整 SUCCESS。浏览器验收数据与 MinIO 对象保留，便于复查；本地后端和前端开发服务器已停止。

真实 LLM smoke test：**未执行（不适用）**。当前 `.env` 为 `LLM_PROVIDER=mock`、`LLM_API_KEY` 空、`LLM_MODEL` 空，没有可用的真实付费 Provider 配置；自动化测试不依赖外部网络或付费 API。

## 10. 前端结果

`VideoDetailView` 已增加：

- M5 分析任务文案与 `SUMMARIZING`/`SAVING_TRANSCRIPT` 阶段类型。
- AI Summary / Overview。
- 按时间显示的 Chapters。
- 按时间显示的 Key Points。
- 分析 SUCCESS 后并行刷新 transcript 与三类 summary API。
- 页面加载时以“是否已有 M5 summary”判断完成状态，因此已有 M4 transcript 的视频仍能创建新的 M5 task。

## 11. 新增/修改文件

主要新增：

```text
backend/src/main/resources/db/migration/V4__create_video_summary_tables.sql
backend/src/main/java/com/videoagent/summary/
  controller/VideoSummaryController.java
  dto/...
  entity/...
  provider/...
  repository/...
  service/...
backend/src/test/java/com/videoagent/summary/...
backend/src/test/java/com/videoagent/analysis/StructuredSummaryInfrastructureIntegrationTest.java
frontend/src/services/summary.ts
frontend/src/types/summary.ts
MILESTONE_5_ACCEPTANCE_REPORT.md
```

主要修改：

```text
.env.example
backend/pom.xml
backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java
backend/src/main/java/com/videoagent/analysis/entity/AnalysisStage.java
backend/src/main/java/com/videoagent/analysis/service/AnalysisProperties.java
backend/src/main/java/com/videoagent/common/exception/ErrorCode.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/analysis/...
backend/src/test/java/com/videoagent/common/health/HealthEndpointIntegrationTest.java
frontend/src/App.vue
frontend/src/styles/main.css
frontend/src/types/analysis.ts
frontend/src/views/VideoDetailView.vue
README.md
IMPLEMENTATION_PLAN.md
```

## 12. 安全与范围审计

- `.env` 未被 Git 跟踪，`.env.example` 仅含 `change-me-*` 示例和空 API Key。
- API Key 只从环境变量绑定；代码、migration、测试与前端中没有真实密钥。
- RocketMQ 消息仍只携带 `taskId`、`videoId`，没有 transcript、视频二进制或 summary 大对象。
- LangChain4j 仅用于 Structured Output，不包含 Memory、Tools、Agent、RAG 或 Embedding。
- M5 没有增加 SSE/WebSocket、多 Topic 流水线或复杂分布式锁。

## 13. 已知非阻断问题

- Flyway 输出警告：当前依赖声明已测试到 MySQL 8.1，而本地为 MySQL 8.4；V1–V4 实际 validate/migrate 与全部集成测试均通过。
- Vite 提示主 chunk 大于 500 KiB，主要来自 Element Plus；不影响当前功能，M5 不为此提前引入额外前端架构。
- Maven 依赖树中旧版 `javassist` effective model 有警告；当前编译、打包与测试通过。
- Mockito 在 JDK 23 上提示未来版本将限制动态 agent attach；当前测试有效，后续依赖升级时可统一配置测试 agent。
- Mock ASR/Mock Summary 用于工程链路验证，内容不代表测试视频真实语义。
- 同一 `videoId + analysis_type + model_version` 只允许一个 task；失败重试和重新分析版本策略不在 M5 范围。

## 14. 最终结论

**Milestone 5：PASS。**

结构化总结链路、默认 Mock、真实 LangChain4j Provider 边界、三表持久化、REST API、Consumer 失败/幂等语义、M3/M4 回归、真实 Docker/FFmpeg E2E 和前端展示均通过验收。Milestone 6 未开始。
