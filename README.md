# VideoAgent

VideoAgent 是一个面向面试学习的 AI 全栈项目。用户上传视频后，系统将异步完成音频提取、语音转文字和结构化总结，并保留可跳转的时间戳。

当前进度：**Milestone 5 — LLM Structured Video Summary（已完成并验收）**。

## 技术栈

- 后端：Java 21、Spring Boot 3、Maven、MyBatis-Plus、Flyway
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus
- 基础设施：MySQL 8、Redis、MinIO、Apache RocketMQ、Docker Compose
- 媒体与转录：FFmpeg、`MediaProcessor`、`AsrProvider`、确定性 `MockAsrProvider`
- 结构化总结：LangChain4j、`VideoSummaryProvider`、确定性 Mock、可配置 OpenAI-compatible Provider
- 后续阶段：SSE、Video RAG

## 本地启动

### 1. 准备环境变量

仓库包含一个仅供本机开发的、已被 `.gitignore` 排除的 `.env`。如需重建：

```powershell
Copy-Item .env.example .env
```

请修改所有 `change-me-*` 值。真实账号、密钥和 API Key 不得提交到仓库。

### 2. 启动基础设施

```powershell
docker compose up -d
docker compose ps
```

本地端口：

| 服务 | 地址/端口 |
| --- | --- |
| MySQL | `localhost:${MYSQL_PORT}`（默认 `3306`；端口冲突时可在 `.env` 覆盖） |
| Redis | `localhost:${REDIS_PORT}`（示例默认 `6380`，避开本机常见的 6379 冲突） |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |
| RocketMQ NameServer | `localhost:9876` |
| RocketMQ Broker | `localhost:10911` |

### 3. 启动后端

需要 Java 21 或更高版本，并需要可执行的 FFmpeg。本机若存在多个 JDK，请先确认 `mvn -version` 使用的 Java 版本。FFmpeg 不在 `PATH` 时，通过 `FFMPEG_PATH` 指向可执行文件：

```powershell
Set-Location backend
$env:FFMPEG_PATH = 'C:\path\to\ffmpeg.exe'
mvn spring-boot:run
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

视频接口：

```text
POST /api/videos             multipart 字段：file，title（可选）
GET  /api/videos
GET  /api/videos/{videoId}
POST /api/videos/{videoId}/analysis
GET  /api/analysis/{taskId}
GET  /api/videos/{videoId}/transcript
GET  /api/videos/{videoId}/summary
GET  /api/videos/{videoId}/chapters
GET  /api/videos/{videoId}/key-points
```

上传链路负责 MP4 校验、MinIO 对象写入和 MySQL 元数据持久化。分析接口会创建 `STRUCTURED_SUMMARY / m5-langchain4j-structured-v1` 持久任务、向 `VIDEO_ANALYZE_TOPIC` 投递仅包含 `taskId`/`videoId` 的消息，并立即返回。Consumer 从 MinIO 下载视频，在受控临时目录中用 FFmpeg 提取 16 kHz 单声道 WAV，由 Mock ASR 生成带时间戳片段，再通过 `VideoSummaryProvider` 生成并事务性保存 Overview、Chapters 与 Key Points。

默认 `LLM_PROVIDER=mock`，无需 API Key。配置 `LLM_PROVIDER=openai`、`LLM_API_KEY` 与 `LLM_MODEL` 后，后端使用 LangChain4j AI Services 的 JSON Schema Structured Output；`LLM_BASE_URL` 可选，调用 timeout 与最多 0–3 次 retry 分别由 `LLM_TIMEOUT`、`LLM_MAX_RETRIES` 控制。若选择 `openai` 但 Key 或 Model 缺失，应用会安全回退到 Mock。

### 4. 启动前端

```powershell
Set-Location frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。Vite 会把 `/api` 请求代理到后端 `8080` 端口。

前端提供视频列表、普通 multipart 上传和元数据详情页面。详情页可以发起 M5 分析，用普通 HTTP 轮询展示排队、提取音频、转录、总结、保存、完成或失败状态，并展示 Overview、按时间排序的 Chapters、Key Points 与 transcript segments。当前尚无视频播放器，因此时间戳仅展示、不提供跳转。单文件上限默认是 500 MB，可通过 `VIDEO_MAX_FILE_SIZE` 与 `VIDEO_MAX_REQUEST_SIZE` 调整。

## 构建与测试

```powershell
Set-Location backend
mvn test
mvn package

Set-Location ../frontend
npm run build
```

后端会可选加载当前目录或仓库根目录的 `.env`，操作系统环境变量仍可覆盖对应配置。当 Docker Compose 基础设施已启动时，可显式运行真实基础设施健康测试：

```powershell
$env:VIDEOAGENT_INFRA_TEST = "true"
mvn "-Dtest=InfrastructureBackedHealthIntegrationTest" test

$env:VIDEOAGENT_M2_INFRA_TEST = "true"
mvn "-Dtest=VideoUploadInfrastructureIntegrationTest" test

$env:VIDEOAGENT_M3_INFRA_TEST = "true"
mvn "-Dtest=AnalysisFrameworkInfrastructureIntegrationTest" test

$env:VIDEOAGENT_FFMPEG_TEST = "true"
$env:FFMPEG_PATH = 'C:\path\to\ffmpeg.exe'
mvn "-Dtest=FfmpegMediaProcessorTest" test

$env:VIDEOAGENT_M4_INFRA_TEST = "true"
mvn "-Dtest=MediaTranscriptionInfrastructureIntegrationTest" test

$env:VIDEOAGENT_M5_INFRA_TEST = "true"
mvn "-Dtest=StructuredSummaryInfrastructureIntegrationTest" test
```

这些测试默认跳过，避免普通单元测试强依赖本机 Docker 或 FFmpeg。M2 基础设施测试检查 MinIO/MySQL 上传链路；M3 基础设施测试回归异步框架；FFmpeg 组件测试覆盖成功、非零退出和超时；M4 基础设施测试覆盖媒体转录链路；M5 基础设施测试覆盖真实上传、RocketMQ、FFmpeg、Mock ASR、Mock Summary、三表入库、结果 API、历史版本、重复消费、Redis 丢失回退和临时文件清理。测试结束会清理自己的临时数据。

## 当前目录结构

```text
backend/             Spring Boot API
  src/main/java/com/videoagent/video/    视频上传、列表与详情
  src/main/java/com/videoagent/storage/  MinIO 对象存储适配
  src/main/java/com/videoagent/analysis/ 异步任务、MQ 与实时进度
  src/main/java/com/videoagent/media/    FFmpeg 媒体处理与临时目录
  src/main/java/com/videoagent/asr/      ASR Provider 与 Mock 实现
  src/main/java/com/videoagent/transcript/ 时间戳字幕持久化与 API
  src/main/java/com/videoagent/summary/  结构化总结 Provider、持久化与 API
frontend/            Vue 3 Web 应用
infra/rocketmq/      RocketMQ Broker 本地配置
docker-compose.yml   本地基础设施
IMPLEMENTATION_PLAN.md
```

## 环境变量

完整清单见 `.env.example`。后端支持通过环境变量覆盖 MySQL、Redis、MinIO、RocketMQ、FFmpeg、分析版本以及 LLM Provider 配置。Mock ASR 与 Mock Summary 均不需要第三方 API Key；真实 LLM Key 只从环境变量读取，不写入配置文件或日志。

## Architecture Decisions

### 为什么使用单体模块化架构？

V1 的重点是跑通上传、异步分析与 AI Provider 链路。单体模块化能保留清晰边界，同时避免微服务带来的部署、远程调用和分布式一致性成本。

### MySQL 与 Redis 的职责

MySQL 保存视频和分析任务等持久业务事实；Redis 只保存带 24 小时 TTL 的实时进度。查询处理中任务时优先读取 Redis，Redis 缺失或不可用时回退 MySQL；MySQL 终态始终优先，避免陈旧缓存覆盖 `SUCCESS/FAILED`。

### 为什么引入 RocketMQ？

长耗时任务不应占用 HTTP 请求。RocketMQ 将任务创建与后台处理解耦；Consumer 使用原子状态转换抢占 PENDING 任务，并对 SUCCESS 消息直接跳过，保证当前框架的重复消费幂等。

### MediaProcessor 与 AsrProvider 的边界

`MediaProcessor` 只负责视频到音频，FFmpeg 参数由后端固定构造并设置超时；它不承担剪辑、生成或复杂转码。`AsrProvider` 只接收音频源并返回时间戳片段。M4 使用确定性 Mock，让没有外部密钥的本地环境也能完整验收 MinIO → FFmpeg → transcript 链路。

### 临时媒体文件生命周期

每个任务在配置的媒体临时根目录下创建随机子目录，只使用系统生成的 `source.mp4` 与 `audio.wav` 文件名。工作区通过 `try-with-resources` 在成功、FFmpeg 失败和 ASR 失败路径统一递归清理，并拒绝删除工作区边界外的路径。

### VideoSummaryProvider 与 Structured Output

业务 Consumer 只依赖 `VideoSummaryProvider`，不直接调用厂商 SDK。Mock Provider 返回确定性结构，保证无 Key 的本地与 CI 链路；真实 Provider 的 LangChain4j 依赖集中在配置与适配器中，使用严格 JSON Schema Structured Output。所有结果在入库前再次校验必填文本、数量、长度与 transcript 时间边界，并统一按时间排序；非法结构使任务进入 `FAILED`，不会产生部分可见结果。

## 已知限制

- 当前只支持普通 multipart MP4 上传，不支持分片、断点续传或秒传。
- 当前 ASR 是确定性 Mock，不是 Whisper 或第三方真实语音识别；字幕内容用于验证工程链路，不代表视频真实语义。
- 当前真实 LLM 仅实现 OpenAI-compatible LangChain4j Provider；自动化与默认本地链路使用确定性 Mock，不依赖付费 API。
- 当前不包含 RAG、Embedding、Agent/Tool Calling、OCR、VLM 或说话人分离。
- 当前前端使用普通轮询，不包含 SSE 或 WebSocket。
- 同一视频、分析类型和模型版本只保留一个任务；当前阶段不提供失败任务的手工重试接口。
- 已有 M3/M4 历史任务不变；M5 使用新的业务键 `STRUCTURED_SUMMARY / m5-langchain4j-structured-v1`。
- 本地默认开发凭据只能用于本机环境。
- MinIO 写成功但 MySQL 写失败时会尝试补偿删除对象；跨资源强一致性不属于当前阶段。

## Roadmap

详见 [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)。每个 Milestone 完成并验收后停止，等待下一步指令。
