# VideoAgent

VideoAgent 是一个面向面试学习的 AI 全栈项目。用户上传视频后，系统将异步完成音频提取、语音转文字和结构化总结，并保留可跳转的时间戳。

当前进度：**Milestone 2 — 视频上传**。

## 技术栈

- 后端：Java 21、Spring Boot 3、Maven、MyBatis-Plus、Flyway
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus
- 基础设施：MySQL 8、Redis、MinIO、Apache RocketMQ、Docker Compose
- 后续阶段：FFmpeg、ASR Provider、LLM Provider、SSE、Video RAG

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
| Redis | `localhost:6379` |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |
| RocketMQ NameServer | `localhost:9876` |
| RocketMQ Broker | `localhost:10911` |

### 3. 启动后端

需要 Java 21 或更高版本。本机若存在多个 JDK，请先确认 `mvn -version` 使用的 Java 版本。

```powershell
Set-Location backend
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
```

上传链路只负责 MP4 校验、MinIO 对象写入和 MySQL 元数据持久化。视频分析不在当前里程碑范围内。

### 4. 启动前端

```powershell
Set-Location frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。Vite 会把 `/api` 请求代理到后端 `8080` 端口。

前端提供视频列表、普通 multipart 上传和元数据详情页面。当前单文件上限默认是 500 MB，可通过 `VIDEO_MAX_FILE_SIZE` 与 `VIDEO_MAX_REQUEST_SIZE` 调整。

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
```

这些测试默认跳过，避免普通单元测试强依赖本机 Docker。M2 基础设施测试会临时上传 MP4、检查 MinIO 和 MySQL，并在结束后清理测试数据。

## 当前目录结构

```text
backend/             Spring Boot API
  src/main/java/com/videoagent/video/    视频上传、列表与详情
  src/main/java/com/videoagent/storage/  MinIO 对象存储适配
frontend/            Vue 3 Web 应用
infra/rocketmq/      RocketMQ Broker 本地配置
docker-compose.yml   本地基础设施
IMPLEMENTATION_PLAN.md
```

## 环境变量

完整清单见 `.env.example`。后端支持通过环境变量覆盖 MySQL、Redis、MinIO、RocketMQ、ASR 与 LLM 配置；ASR/LLM 默认使用 `mock`，真实 Provider 将在对应 Milestone 接入。

## Architecture Decisions

### 为什么使用单体模块化架构？

V1 的重点是跑通上传、异步分析与 AI Provider 链路。单体模块化能保留清晰边界，同时避免微服务带来的部署、远程调用和分布式一致性成本。

### MySQL 与 Redis 的职责

MySQL 将保存视频、分析任务和 AI 结果等持久业务事实；Redis 只保存高频、短生命周期的实时进度与提交去重标记。Redis 数据丢失不会导致任务记录丢失。

### 为什么引入 RocketMQ？

ASR 与 LLM 是长耗时外部调用，HTTP 请求不应同步等待。RocketMQ 将任务创建与后台处理解耦，并为重试、削峰和 Consumer 扩展提供基础。具体幂等与失败策略在 Milestone 3 和 7 完成。

## 已知限制

- 当前只支持普通 multipart MP4 上传，不支持分片、断点续传或秒传。
- 当前详情页只展示视频元数据；视频分析、播放地址与结果展示尚未实现。
- 本地默认开发凭据只能用于本机环境。
- MinIO 写成功但 MySQL 写失败时会尝试补偿删除对象；跨资源强一致性不属于当前阶段。

## Roadmap

详见 [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)。每个 Milestone 完成并验收后停止，等待下一步指令。
