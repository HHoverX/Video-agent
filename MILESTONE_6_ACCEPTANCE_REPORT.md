# Milestone 6 Acceptance Report — SSE Real-time Analysis Progress

## 结论

**PASS**

Milestone 6 已在限定范围内完成。Spring MVC SSE、浏览器 EventSource、终态关闭、断开/超时清理、Redis 缺失时的 MySQL 初始状态回退，以及有限 GET polling fallback 均已实现并验证。Milestone 3–5 的异步任务、媒体转录和结构化总结链路未被改变或破坏。

可以进入 Milestone 7，但本次按要求停止，不实施 Milestone 7。

## Git 检查点与秘密文件

- 开始 M6 前工作树干净。
- Milestone 5 已创建独立提交：`c3c6576 feat: complete milestone 5 structured video summary`。
- `git ls-files .env` 无输出；`.env` 未被跟踪，且由 `.gitignore` 排除。
- `.env.example` 只包含占位凭据和空 API Key；M6 仅新增 `VIDEO_ANALYSIS_SSE_TIMEOUT=30m`。
- M6 变更尚未提交，留待下一阶段开始前按独立检查点提交。

## 实现结果

### 后端 SSE

- 新增 `GET /api/analysis/{taskId}/events`，响应类型为 `text/event-stream`。
- 所有消息使用 `event: progress`，data 包含 `taskId`、`videoId`、`status`、`stage`、`progress`、`message`、`errorCode` 和 `errorMessage`。
- 订阅时先读取状态、注册 emitter、发送初始事件，再二次读取状态，避免“首次查询和注册之间”的更新竞态。
- Consumer 与任务创建服务经统一的 `AnalysisProgressUpdateService` 写 Redis 并广播 SSE；原 MySQL 状态更新顺序保持不变。
- SUCCESS/FAILED 在发送最终事件后关闭 emitter。
- completion、error、timeout、主动断开和发送异常都会移除订阅；SSE 失败不会传播到 Consumer 或改变任务状态。
- emitter timeout 默认 30 分钟，可通过 `VIDEO_ANALYSIS_SSE_TIMEOUT` 配置。
- 未引入轮询线程、WebFlux、WebSocket、Redis Pub/Sub、新 MQ Topic 或额外基础设施。

### 状态职责

- **MySQL**：任务最终业务事实源。
- **Redis**：带 TTL 的实时进度快照。
- **SSE**：当前应用进程中的实时观察通道。
- Redis 快照缺失时，初始 SSE 事件和现有 GET 查询都通过 `AnalysisQueryService` 回退 MySQL。

### 前端

- 发起分析后优先建立 `EventSource`。
- progress 事件实时更新状态、阶段、进度和消息。
- SUCCESS/FAILED 主动关闭 EventSource；SUCCESS 自动并发刷新 transcript、summary、chapters 和 key-points。
- 活动 taskId 保存于 sessionStorage，页面刷新后先通过 GET 恢复状态，再为活动任务重新建立 SSE。
- SSE error 时立即关闭浏览器默认自动重连，退化为每秒一次、最多 180 次的 GET 查询；不实现复杂重连框架。
- 页面卸载时清理 EventSource 和 polling timer。

## 自动化测试

覆盖要求：

1. SSE endpoint `Content-Type` 正确。
2. 订阅建立后发送初始状态。
3. PROCESSING progress 可发送。
4. SUCCESS 最终事件发送并关闭。
5. FAILED 最终事件发送并关闭。
6. 客户端 completion/error/timeout/发送失败会清理订阅，且广播异常被隔离。
7. Redis 无快照时，初始 SSE 状态来自 MySQL。
8. 原 `GET /api/analysis/{taskId}` Controller 与查询回退测试继续通过。
9. Consumer SUCCESS 幂等、FFmpeg/ASR/transcript/summary 和 M3–M5 测试继续通过。

结果：

- 完整后端套件：`66` tests，`0` failures，`0` errors；其中 `55` 执行通过，`11` 个真实基础设施/FFmpeg 测试按环境变量设计跳过。
- M6 真实基础设施测试另行显式启用：`1` test，`0` failures，`0` errors，`0` skipped。
- 前端 TypeScript 检查与 Vite production build：PASS。

## 真实基础设施验收

基础设施状态：MySQL、Redis、MinIO、RocketMQ NameServer、RocketMQ Broker 均为 healthy。

真实测试链路：

```text
上传 20 秒 MP4
→ POST analysis
→ RocketMQ Consumer
→ MinIO 下载
→ FFmpeg 提取音频
→ Mock ASR
→ Mock Video Summary Provider
→ SSE progress
→ MySQL SUCCESS
→ Transcript / Summary / Chapters / Key Points API
```

HTTP SSE 流实际捕获并断言以下阶段：

```text
PREPARING
→ EXTRACTING_AUDIO
→ TRANSCRIBING
→ SUMMARIZING
→ SAVING
→ DONE / 100 / SUCCESS
```

终态事件后服务端正常关闭流。随后删除对应 Redis progress key，再次订阅 SSE，只收到一个来自 MySQL 的 `DONE / 100 / SUCCESS` 初始终态事件并关闭。

集成测试创建的 MinIO、MySQL、Redis 和临时媒体数据已在测试清理阶段删除。

## 浏览器验收

- 通过真实上传页面上传 `m6-browser.mp4`，生成视频 `#48`。
- 在详情页点击“开始 AI 分析”，生成任务 `#47`。
- 页面通过 M6 EventSource 流程完成分析，并自动展示：
  - `DONE / 100 / 已完成`
  - 3 个 timestamp transcript segments
  - Overview
  - 2 个 Chapters
  - 3 个 Key Points
- 刷新详情页后，四类结果仍能从持久化 API 正确加载。
- 浏览器控制台：0 error，0 warning。
- 浏览器验收数据保留在本地环境，便于人工复查。

## 实际执行过的主要命令

```powershell
git status --short
git ls-files .env
git add .
git commit -m "feat: complete milestone 5 structured video summary"

mvn -q -DskipTests clean compile
mvn -q -DskipTests test-compile
mvn -q "-Dtest=AnalysisEventBroadcasterTest,AnalysisEventServiceTest,AnalysisProgressUpdateServiceTest,AnalysisEventControllerTest,AnalysisCommandServiceTest,AnalysisTaskProcessorTest,AnalysisControllerTest" test
mvn -q test

docker compose up -d
docker compose ps

$env:VIDEOAGENT_M6_INFRA_TEST='true'
$env:FFMPEG_PATH='D:\Vibe Coding\Video agent\tmp\ffmpeg-local\ffmpeg-9.0-essentials_build\bin\ffmpeg.exe'
mvn -q "-Dtest=AnalysisSseInfrastructureIntegrationTest" test

npm run build
git diff --check
```

此外实际隐藏启动 Spring Boot 与 Vite，并使用本地浏览器完成上传、分析、结果刷新和页面重载验收。

## 新增文件

```text
backend/src/main/java/com/videoagent/analysis/controller/AnalysisEventController.java
backend/src/main/java/com/videoagent/analysis/dto/AnalysisProgressEventResponse.java
backend/src/main/java/com/videoagent/analysis/event/AnalysisEventBroadcaster.java
backend/src/main/java/com/videoagent/analysis/service/AnalysisEventProperties.java
backend/src/main/java/com/videoagent/analysis/service/AnalysisEventService.java
backend/src/main/java/com/videoagent/analysis/service/AnalysisProgressUpdateService.java
backend/src/test/java/com/videoagent/analysis/AnalysisSseInfrastructureIntegrationTest.java
backend/src/test/java/com/videoagent/analysis/controller/AnalysisEventControllerTest.java
backend/src/test/java/com/videoagent/analysis/event/AnalysisEventBroadcasterTest.java
backend/src/test/java/com/videoagent/analysis/service/AnalysisEventServiceTest.java
backend/src/test/java/com/videoagent/analysis/service/AnalysisProgressUpdateServiceTest.java
frontend/src/composables/useAnalysisEvents.ts
MILESTONE_6_ACCEPTANCE_REPORT.md
```

## 修改文件

```text
.env.example
IMPLEMENTATION_PLAN.md
README.md
backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java
backend/src/main/java/com/videoagent/analysis/service/AnalysisCommandService.java
backend/src/main/resources/application.yml
backend/src/test/java/com/videoagent/analysis/consumer/AnalysisTaskProcessorTest.java
backend/src/test/java/com/videoagent/analysis/service/AnalysisCommandServiceTest.java
frontend/src/App.vue
frontend/src/services/analysis.ts
frontend/src/types/analysis.ts
frontend/src/views/VideoDetailView.vue
```

## 已知问题与边界

- SSE 广播器是单应用进程内实现；M6 明确禁止 Redis Pub/Sub。未来多实例部署需要粘性路由或独立评审跨实例通知方案。
- 未增加 heartbeat。默认 30 分钟 emitter timeout 足以覆盖当前任务；代理更短的空闲超时会触发前端有限 GET fallback。
- 真实链路很快，人工观察时某些中间阶段停留时间很短；自动化真实 HTTP SSE 测试已逐一捕获并断言规定阶段。
- Vite 仍有既存的大 chunk warning，不影响构建或 M6 功能，未在本阶段做无关拆包优化。
- Flyway 对 MySQL 8.4 显示既存“建议升级”警告；4 个历史 migration 均成功校验，未修改历史 migration。
- RocketMQ 本地 Broker 保留过往测试消息；新测试 consumer group 首次启动会记录已清理任务的 not-found 并安全确认，这不影响本次新任务或幂等语义。

## 范围审计

未实现 WebSocket、Redis Pub/Sub、Kafka、新 MQ Topic、RAG、Embedding、Agent、Tool Calling、Transactional Outbox、分布式 Session、WebFlux 全量迁移或 Milestone 7 功能。

