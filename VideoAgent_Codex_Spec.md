# VideoAgent V1 — Codex 可执行项目规格书

> 目标：先通过 Vibe Coding 快速完成一个可运行、可演示、可部署的 AI 全栈项目；再以该项目为主线，自顶向下拆解并学习 Java 后端、Redis、RocketMQ、MySQL、对象存储、AI 应用与 RAG 等面试知识。
>
> 重要原则：项目首先服务于“面试学习”，不是追求最先进的视频理解算法，也不是堆砌技术。每引入一个组件，都必须能回答“为什么需要它、解决了什么问题、替代方案是什么”。

---

## 0. 给 Codex 的总指令

你是一名资深 Java 全栈 / AI 应用工程师。请按照本文档完成一个名为 **VideoAgent** 的项目。

你的任务不是一次性生成大量不可维护代码，而是：

1. 先检查当前仓库结构与已有代码。
2. 根据本文档给出简短实施计划。
3. 按阶段实现，每完成一个阶段：
   - 编译/运行相关代码；
   - 执行已有测试；
   - 为核心业务补充必要测试；
   - 汇报修改文件、完成内容、未完成项；
   - 不要擅自增加本文档没有要求的复杂基础设施。
4. 优先保证：
   - 能运行；
   - 业务链路完整；
   - 模块边界清晰；
   - 代码便于后续学习与讲解；
   - Redis、RocketMQ 的使用具有真实业务理由，而不是为了“技术栈好看”。
5. 所有密钥、账号、第三方 API Key 必须使用环境变量或 `.env.example` 占位，禁止写死。
6. 对 ASR、LLM 等第三方 AI 能力使用接口抽象，支持 Mock Provider，保证没有真实 API Key 时仍能完成本地开发和测试。
7. 第一版禁止过度设计。除非本文档明确要求，不要擅自加入：
   - Kafka
   - Kubernetes
   - Elasticsearch
   - 多 Agent
   - OCR
   - VLM 视频逐帧理解
   - 自训练 ASR
   - 复杂微服务拆分
8. 如果需求与现有代码冲突，先说明冲突，再选择最小改动方案。

---

# 1. 项目定位

## 1.1 产品名称

**VideoAgent**

## 1.2 一句话描述

用户上传一个视频后，系统异步完成音频提取、语音转文字和 AI 总结，并支持查看处理进度、结构化摘要、带时间戳的字幕，以及基于视频文本内容进行问答。

## 1.3 项目目的

该项目是一个 **面向面试学习的 AI 全栈项目**。

项目需要自然覆盖：

- Spring Boot
- MySQL
- Redis
- RocketMQ
- MinIO
- MyBatis-Plus
- SSE
- FFmpeg
- LangChain4j
- LLM API
- ASR API
- RAG（后续阶段）
- Docker Compose
- Vue 3

重点学习方向：

- Java Web 请求链路
- 数据库设计与索引
- Redis 使用场景
- 消息队列异步化
- 消费幂等
- 重试与异常处理
- 大文件上传
- 对象存储
- AI API 工程化接入
- 长耗时任务设计
- RAG 基本链路

---

# 2. V1 功能范围

## 2.1 必须实现

### 用户侧

1. 视频上传
2. 视频列表
3. 视频详情
4. 发起 AI 分析
5. 查看实时分析进度
6. 查看视频字幕
7. 查看 AI 总结
8. 查看关键点
9. 查看自动章节
10. 基于当前视频内容进行问答

### 系统侧

1. 视频文件保存至 MinIO
2. MySQL 保存视频与任务元数据
3. Redis 保存短期高频状态
4. RocketMQ 异步执行视频分析
5. FFmpeg 从视频提取音频
6. 调用 ASR Provider 获得带时间戳 transcript
7. 调用 LLM Provider 生成结构化总结
8. SSE 向前端推送/提供任务进度
9. 分析任务支持失败状态
10. Consumer 必须具备业务幂等能力

---

# 3. 明确不做的功能

V1 不实现：

- 自己训练 ASR 模型
- Whisper 模型内部优化
- GPU 推理优化
- OCR
- 视频关键帧视觉理解
- 人脸识别
- Speaker Diarization
- 视频生成/剪辑
- 多 Agent 协作
- LangGraph
- Kubernetes
- 微服务拆分
- 复杂权限系统
- 支付
- 推荐系统

ASR 在本项目中的定位：

> **第三方基础能力，不是项目核心技术亮点。**

系统只负责：

- 提取音频；
- 调用 ASR；
- 处理超时/失败；
- 保存 transcript；
- 利用 timestamp 生成章节和定位信息。

---

# 4. 推荐技术栈

## 后端

- Java 21
- Spring Boot 3.x
- Maven
- MyBatis-Plus
- MySQL 8
- Redis
- Redisson（仅确有锁需求时使用）
- Apache RocketMQ
- MinIO
- LangChain4j
- SSE
- FFmpeg

## 前端

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Axios
- Element Plus

## 基础设施

使用 Docker Compose 启动：

- MySQL
- Redis
- RocketMQ NameServer
- RocketMQ Broker
- MinIO

后端、前端可以先本地运行。

---

# 5. 总体架构

```text
                         Vue 3
                           |
                    REST API / SSE
                           |
                    Spring Boot API
                           |
          +----------------+----------------+
          |                |                |
        MySQL            Redis            MinIO
          |
          |
       RocketMQ
          |
          v
   Video Analysis Consumer
          |
          +--> FFmpeg
          |
          +--> ASR Provider
          |
          +--> LLM Provider
          |
          +--> MySQL
          |
          +--> Redis Progress
```

核心思想：

- MySQL：最终事实与持久状态
- Redis：高频、短生命周期状态
- RocketMQ：长耗时 AI 任务异步化
- MinIO：视频/音频对象存储
- ASR：外部黑盒服务
- LLM：摘要与问答

---

# 6. 核心业务链路

## 6.1 上传视频

```text
Frontend
   |
POST /api/videos
   |
Spring Boot
   |
MinIO Upload
   |
MySQL INSERT video
   |
Return videoId
```

V1 可以先支持普通 multipart 上传。

后续增强阶段再加入：

- 分片上传
- 断点续传
- 秒传

不要第一阶段就把上传流程做得过重。

---

## 6.2 发起视频分析

```text
POST /api/videos/{videoId}/analysis
              |
              v
        校验 video 是否存在
              |
              v
        防重复分析判断
              |
              v
      MySQL 创建 analysis_task
              |
              v
        RocketMQ Producer
              |
              v
      VIDEO_ANALYZE_TOPIC
              |
              v
        立即返回 taskId
```

HTTP 接口不等待 ASR/LLM 完成。

示例响应：

```json
{
  "taskId": 10001,
  "videoId": 123,
  "status": "PENDING"
}
```

---

## 6.3 MQ Consumer 分析流程

```text
RocketMQ Message(taskId)
        |
        v
查询 analysis_task
        |
        +--> SUCCESS -> 直接结束（幂等）
        |
        v
状态改 PROCESSING
        |
        v
从 MinIO 获取视频
        |
        v
FFmpeg 提取音频
        |
        v
ASR Provider
        |
        v
保存 transcript
        |
        v
LLM Summarizer
        |
        v
保存 summary / chapters / key points
        |
        v
状态 SUCCESS
```

发生异常：

```text
Exception
   |
   v
记录 error_message
   |
   v
状态 FAILED
```

可重试异常与不可重试异常必须区分。

---

# 7. 任务状态机

`analysis_task.status`：

```text
PENDING
   |
   v
PROCESSING
   |
   +------> FAILED
   |
   v
SUCCESS
```

可增加 `stage`：

```text
QUEUED
EXTRACTING_AUDIO
TRANSCRIBING
SUMMARIZING
SAVING
DONE
```

Redis 用于展示更细粒度实时进度。

示例：

```text
video:analysis:progress:{taskId}
```

Value：

```json
{
  "status": "PROCESSING",
  "stage": "TRANSCRIBING",
  "progress": 45,
  "message": "正在进行语音识别"
}
```

Redis Key 设置合理 TTL。

---

# 8. Redis 设计

Redis 必须解决真实业务问题，不得只为了技术栈而使用。

## 8.1 V1 必做

### A. AI 分析任务实时进度

Key：

```text
video:analysis:progress:{taskId}
```

作用：

- 前端高频读取处理进度；
- 避免每秒轮询 MySQL；
- MySQL 仍然保存最终任务状态。

原则：

> Redis 不是任务状态唯一事实源。

如果 Redis 数据丢失：

- 任务本身仍可根据 MySQL 恢复；
- 只影响实时进度展示。

### B. 防止短时间重复提交

可使用：

```text
video:analysis:submit:{videoId}
```

使用 `SET NX EX` 做短期请求去重。

但是最终业务幂等必须依赖 MySQL 任务状态或唯一约束，不能只依赖 Redis 锁。

---

## 8.2 V1.5 可增加

### 接口限流

例如：

```text
rate:ai:user:{userId}
```

用于限制用户单位时间内的 AI 请求。

优先实现简单固定窗口。

后续学习阶段再升级：

- Lua
- 滑动窗口
- Token Bucket

### AI 结果缓存

例如：

```text
summary:cache:{videoHash}:{modelVersion}
```

不要把缓存作为唯一结果存储。

---

# 9. RocketMQ 设计

## 9.1 V1 Topic

只创建一个核心 Topic：

```text
VIDEO_ANALYZE_TOPIC
```

消息体只携带必要信息：

```json
{
  "taskId": 10001,
  "videoId": 123
}
```

不要把大段 transcript 或视频二进制放入 MQ。

## 9.2 为什么使用 MQ

业务理由：

1. ASR + LLM 是长耗时调用；
2. HTTP 不应该阻塞等待几十秒甚至数分钟；
3. 消费者可独立扩容；
4. 支持失败重试；
5. 可承接突发任务，起到削峰作用；
6. 服务重启后任务仍具备恢复基础。

## 9.3 Consumer 幂等

Consumer 收到消息后必须先判断：

```text
task.status == SUCCESS ?
```

若为 SUCCESS：

```text
return
```

若为 PROCESSING，需要根据实现避免重复并发处理。

建议数据库增加业务唯一约束，例如：

```text
(video_id, analysis_type, model_version)
```

或使用 taskId 作为天然业务幂等键。

## 9.4 重试原则

只对明确的瞬时错误重试，例如：

- 网络超时
- 第三方服务 429
- 第三方服务 5xx

不可无限重试。

达到上限：

```text
analysis_task.status = FAILED
```

保存：

```text
error_code
error_message
retry_count
```

---

# 10. 数据库设计

至少需要以下表。

## 10.1 `video`

建议字段：

```text
id
user_id                nullable in V1
title
original_filename
object_key
file_size
duration_seconds
mime_type
file_hash
status
created_at
updated_at
```

索引：

```text
PRIMARY KEY(id)
INDEX(created_at)
INDEX(file_hash)
```

---

## 10.2 `analysis_task`

```text
id
video_id
analysis_type
model_version
status
stage
progress
retry_count
error_code
error_message
started_at
finished_at
created_at
updated_at
```

建议索引：

```text
INDEX(video_id)
INDEX(status)
UNIQUE(video_id, analysis_type, model_version)
```

注意：

如果希望同一视频可重新分析，需要将唯一约束策略设计为可支持版本或创建新的分析版本。

---

## 10.3 `video_transcript_segment`

不要把整段 transcript 只塞进一个超大字段。

```text
id
video_id
task_id
segment_index
start_ms
end_ms
text
created_at
```

索引：

```text
INDEX(video_id, segment_index)
INDEX(video_id, start_ms)
```

---

## 10.4 `video_summary`

```text
id
video_id
task_id
overview
created_at
updated_at
```

---

## 10.5 `video_chapter`

```text
id
video_id
task_id
title
summary
start_ms
end_ms
chapter_index
```

---

## 10.6 `video_key_point`

```text
id
video_id
task_id
content
start_ms
end_ms
point_index
```

---

## 10.7 后续 RAG 表

V1.5 再设计：

```text
video_embedding_chunk
conversation
chat_message
```

---

# 11. ASR Provider 设计

ASR 必须被抽象为普通外部能力。

接口示例：

```java
public interface AsrProvider {

    TranscriptionResult transcribe(AudioSource audio);

}
```

返回结构：

```java
public record TranscriptSegment(
    long startMs,
    long endMs,
    String text
) {}
```

```java
public record TranscriptionResult(
    String language,
    List<TranscriptSegment> segments
) {}
```

至少实现：

```text
MockAsrProvider
RealAsrProvider
```

本地没有 API Key 时默认使用 Mock。

项目 README 中不要宣传：

- 自研 ASR
- Whisper 优化
- 模型推理优化

项目重点是：

> ASR 的工程接入、异步调用、超时、重试和结果结构化。

---

# 12. LLM Provider 设计

不要在 Service 内散落具体厂商 SDK 调用。

定义：

```java
public interface VideoSummaryProvider {

    VideoSummaryResult summarize(
        VideoSummaryRequest request
    );

}
```

结构化输出必须包含：

```json
{
  "overview": "...",
  "chapters": [
    {
      "title": "...",
      "startMs": 0,
      "endMs": 120000,
      "summary": "..."
    }
  ],
  "keyPoints": [
    {
      "content": "...",
      "startMs": 30000,
      "endMs": 80000
    }
  ]
}
```

使用 LangChain4j 时优先使用结构化输出，而不是解析自由文本。

同时实现：

```text
MockVideoSummaryProvider
```

保证无真实 LLM Key 也可以演示完整链路。

---

# 13. FFmpeg 设计

FFmpeg 只负责基础媒体处理：

```text
视频 -> 音频
```

封装：

```java
public interface MediaProcessor {

    AudioExtractResult extractAudio(Path videoPath);

}
```

禁止让 LLM 生成 FFmpeg shell 命令。

所有命令必须由后端确定性构造。

需要：

- 设置执行超时；
- 捕获 exit code；
- 保存 stderr；
- 临时文件执行完成后清理；
- 文件路径防止命令注入。

---

# 14. SSE 进度接口

建议：

```text
GET /api/analysis/{taskId}/events
```

返回：

```text
event: progress
data: {
  "stage": "TRANSCRIBING",
  "progress": 45,
  "message": "正在进行语音识别"
}
```

也提供普通查询接口兜底：

```text
GET /api/analysis/{taskId}
```

SSE 断开不影响实际任务。

---

# 15. REST API 草案

## Video

```text
POST   /api/videos
GET    /api/videos
GET    /api/videos/{videoId}
DELETE /api/videos/{videoId}
```

## Analysis

```text
POST /api/videos/{videoId}/analysis
GET  /api/analysis/{taskId}
GET  /api/analysis/{taskId}/events
```

## Result

```text
GET /api/videos/{videoId}/summary
GET /api/videos/{videoId}/transcript
GET /api/videos/{videoId}/chapters
GET /api/videos/{videoId}/key-points
```

## Chat — V1.5

```text
POST /api/videos/{videoId}/chat
GET  /api/videos/{videoId}/conversations
```

---

# 16. 前端页面

## 16.1 首页 / 视频列表

显示：

- 文件名
- 上传时间
- 视频时长
- 分析状态
- 查看详情
- 发起/重新分析

## 16.2 上传页面

- 拖拽上传
- 上传进度
- 文件大小提示
- 格式校验

## 16.3 视频详情页

布局建议：

```text
+----------------------+----------------------+
|                      | AI Summary           |
|    Video Player      |                      |
|                      | Overview             |
+----------------------+ Chapters             |
| Processing Status    | Key Points           |
|                      |                      |
+----------------------+----------------------+

Transcript

00:13  xxxx
00:28  xxxx

Ask This Video
[_______________________________]
```

点击 chapter / timestamp：

```text
video.currentTime = timestamp
```

---

# 17. 后端模块结构建议

不要一上来拆成微服务。

单体模块化结构即可：

```text
backend/
└── src/main/java/.../videoagent
    ├── common
    │   ├── exception
    │   ├── response
    │   └── config
    │
    ├── video
    │   ├── controller
    │   ├── service
    │   ├── repository
    │   ├── entity
    │   └── dto
    │
    ├── analysis
    │   ├── controller
    │   ├── service
    │   ├── consumer
    │   ├── producer
    │   ├── entity
    │   └── dto
    │
    ├── storage
    │   └── MinioStorageService
    │
    ├── media
    │   └── FfmpegMediaProcessor
    │
    ├── ai
    │   ├── asr
    │   ├── llm
    │   └── model
    │
    └── chat
        └── ...
```

原则：

- Controller 不写复杂业务逻辑；
- Service 管业务编排；
- Provider 隔离第三方 API；
- MQ Consumer 不承担全部业务细节，应调用 Service；
- 数据库 Entity 不直接暴露给前端。

---

# 18. 错误处理

统一错误码，例如：

```text
VIDEO_NOT_FOUND
VIDEO_UPLOAD_FAILED
VIDEO_FORMAT_NOT_SUPPORTED
ANALYSIS_ALREADY_RUNNING
ANALYSIS_NOT_FOUND
FFMPEG_FAILED
ASR_TIMEOUT
ASR_FAILED
LLM_TIMEOUT
LLM_FAILED
STORAGE_ERROR
INTERNAL_ERROR
```

第三方 API：

- 设置 connect timeout；
- 设置 read timeout；
- 不允许无限重试；
- 日志中禁止记录 API Key；
- 保存必要的 requestId / provider error code。

---

# 19. 日志要求

核心日志必须携带：

```text
videoId
taskId
stage
```

示例：

```text
[taskId=10001][videoId=123] start transcription
```

禁止输出：

- API Key
- 完整 Authorization Header
- 用户隐私数据
- 大段二进制内容

---

# 20. Docker Compose

`docker-compose.yml` 至少包含：

```text
mysql
redis
minio
rocketmq-namesrv
rocketmq-broker
```

提供：

```text
.env.example
```

包含：

```text
MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=videoagent

REDIS_HOST=
REDIS_PORT=

MINIO_ENDPOINT=
MINIO_ACCESS_KEY=
MINIO_SECRET_KEY=
MINIO_BUCKET=

ROCKETMQ_NAMESRV_ADDR=

ASR_PROVIDER=mock
ASR_API_KEY=

LLM_PROVIDER=mock
LLM_API_KEY=
LLM_MODEL=
```

---

# 21. V1 实施顺序

Codex 必须按照以下阶段开发，不要一次性完成全部。

## Milestone 1 — 项目骨架

完成：

- Spring Boot 后端
- Vue 3 前端
- Docker Compose
- MySQL
- Redis
- MinIO
- RocketMQ
- 健康检查

验收：

```text
docker compose up -d
```

基础设施能够启动。

后端：

```text
GET /api/health
```

返回成功。

---

## Milestone 2 — 视频上传

完成：

```text
Frontend -> Spring Boot -> MinIO -> MySQL
```

验收：

- 上传 MP4；
- MinIO 中出现对象；
- MySQL video 表出现记录；
- 视频列表可以查看。

---

## Milestone 3 — 异步分析框架

完成：

```text
POST analysis
  -> MySQL task
  -> RocketMQ
  -> Consumer
```

此阶段先不接真实 ASR。

Consumer 模拟：

```text
sleep
progress 20
progress 50
progress 80
SUCCESS
```

Redis 保存进度。

验收：

- HTTP 快速返回 taskId；
- Consumer 后台处理；
- Redis 进度变化；
- MySQL 最终 SUCCESS。

---

## Milestone 4 — FFmpeg + Mock ASR

完成：

```text
Video
 -> FFmpeg Audio
 -> MockAsrProvider
 -> transcript segments
```

验收：

- transcript 正确入库；
- 具备 timestamp；
- FFmpeg 失败能够把 task 置为 FAILED。

---

## Milestone 5 — LLM 摘要

完成：

```text
Transcript
 -> VideoSummaryProvider
 -> summary
 -> chapters
 -> key points
```

先使用 Mock Provider，再允许配置真实 API。

验收：

- 详情页展示 overview；
- 展示章节；
- 展示关键点；
- 点击时间戳可跳转播放器。

---

## Milestone 6 — SSE

完成：

```text
Redis Progress
 -> SSE
 -> Frontend
```

验收：

前端能显示：

```text
排队中
提取音频
语音识别
AI 总结
完成
```

---

## Milestone 7 — 可靠性

补充：

- Consumer 幂等
- 重复分析保护
- 第三方 API 超时
- 有界重试
- 错误码
- 必要日志
- 单元测试/集成测试

---

## Milestone 8 — Video RAG（V1.5）

只有 V1 完整稳定后再实现。

流程：

```text
Transcript
   |
chunk
   |
embedding
   |
vector store
   |
retrieve
   |
LLM answer
```

要求：

- 回答只基于视频 transcript；
- 返回相关 timestamp；
- transcript 过长时不把全文直接塞给 LLM。

向量数据库优先考虑：

```text
PostgreSQL + pgvector
```

若当前项目仍使用 MySQL，可单独评估，不要为了 RAG 立即大规模迁移数据库。

---

# 22. 面试导向的技术优先级

## A 级：必须深入掌握

```text
Spring Boot
MySQL
Redis
RocketMQ
异步任务
幂等
重试
缓存
分布式基础
Agent / LLM 应用
```

需要掌握：

- 为什么使用；
- 如何实现；
- 常见故障；
- 替代方案；
- 性能问题；
- 面试追问。

## B 级：需要懂原理与工程使用

```text
MinIO
SSE
LangChain4j
RAG
Docker Compose
```

## C 级：外部能力

```text
ASR
FFmpeg
LLM Provider SDK
```

ASR 不深入：

- 模型训练；
- Whisper 网络结构；
- 声学模型；
- GPU 推理优化。

FFmpeg 不深入编解码算法。

---

# 23. 必须能回答的架构问题

Codex 在 README 的 `Architecture Decisions` 中记录答案。

### Redis

1. 为什么分析进度放 Redis，不全部写 MySQL？
2. Redis 数据丢失会不会导致任务丢失？
3. Redis 与 MySQL 各自承担什么角色？
4. 为什么重复提交不能只依赖 Redis 分布式锁？
5. Redis 挂了系统如何降级？

### RocketMQ

1. 为什么视频分析要异步？
2. 为什么不用 Controller 同步等待？
3. 为什么不用单机线程池？
4. Consumer 重复消费怎么办？
5. 消费到一半服务宕机怎么办？
6. 第三方 ASR 调用失败怎么办？
7. 消息积压怎么办？
8. DB 写成功但 MQ 发送失败怎么办？

V1 对最后一个问题可以先记录为“已知一致性风险”。

后续学习阶段再实现：

```text
Transactional Outbox
```

不要为了炫技在 V1 直接实现。

### ASR

1. ASR 在系统中的作用是什么？
2. 输入输出是什么？
3. 为什么需要 timestamp？
4. 为什么作为异步任务？
5. ASR 超时怎么处理？
6. 为什么项目不自己训练 ASR？

---

# 24. README 必须包含

最终 README 至少有：

1. 项目介绍
2. 功能截图
3. 技术栈
4. 系统架构图
5. 核心业务链路
6. 本地启动方式
7. Docker Compose 使用方式
8. 环境变量说明
9. Redis 使用场景
10. RocketMQ 使用场景
11. AI Provider 抽象
12. Mock 模式
13. 已知限制
14. 后续 Roadmap
15. Architecture Decisions

不要写未经测量的夸张数字，例如：

```text
性能提升 99%
接口从 60 秒降至 50ms
QPS 提升 20 倍
```

除非项目中存在可复现 benchmark。

---

# 25. 测试要求

至少覆盖：

## Unit Test

- AnalysisService
- Redis progress service
- ASR mock provider
- LLM mock provider
- task state transition

## Integration Test

- 创建视频记录
- 创建分析任务
- 重复请求不会生成重复有效任务
- Consumer 重复处理 SUCCESS task 时直接跳过
- 失败后 task 状态正确

如果 RocketMQ / MinIO 的完整 Testcontainers 成本过高，V1 可优先 Mock 外部依赖，但核心业务必须可测。

---

# 26. Codex 编码规则

1. 不要生成 1000 行 God Service。
2. Controller 保持薄。
3. 不要为了“设计模式”创建大量无意义接口。
4. 只有第三方能力、存储、媒体处理等明确需要替换的边界才抽象接口。
5. DTO / Entity 分离。
6. 使用统一异常处理。
7. 使用 Bean Validation。
8. 数据库迁移建议使用 Flyway。
9. 不要自动执行危险删除。
10. 不要删除用户已有代码，除非明确说明原因。
11. 修改数据库 schema 时同步 migration。
12. 每个 Milestone 保持可运行。
13. Mock Provider 必须能让项目无付费 API 运行。
14. 写代码时优先清晰度，而不是炫技。
15. 对所有非显然的架构决策写简短注释或 ADR。

---

# 27. 第一阶段项目亮点

V1 完成后，对外只主打以下 4 个亮点：

### 1. 长耗时 AI 任务异步化

基于 RocketMQ 将视频转录与 LLM 总结从同步 HTTP 链路中解耦，API 负责创建任务，Consumer 后台执行处理。

### 2. Redis 实时任务状态

使用 Redis 保存视频分析的高频实时进度，MySQL 保存最终业务状态，实现“缓存/实时状态”和“持久事实”职责分离。

### 3. AI Provider 工程化抽象

ASR 和 LLM 通过 Provider 接口接入，支持 Mock / Real 实现、超时、有限重试与错误状态管理，避免业务代码与具体厂商 SDK 强耦合。

### 4. 带时间戳的视频结构化总结

将 ASR transcript 转换为 overview、chapter、key point，并保留 timestamp，使用户能够从摘要定位回原视频。

---

# 28. 后续可升级亮点

V1 完成并掌握后再逐步添加：

```text
分片上传
断点续传
AI 限流
Video RAG
Transactional Outbox
Dead Letter Queue
Redis Lua 限流
结果缓存
分析版本管理
任务补偿
```

原则：

> 每次只增加一个新问题和一个对应技术。

---

# 29. Codex 第一次执行任务

现在请先不要直接实现所有代码。

第一步执行：

1. 检查当前仓库。
2. 输出现有目录结构。
3. 判断是新项目还是已有项目。
4. 根据本文档生成 `IMPLEMENTATION_PLAN.md`。
5. 将任务拆成 Milestone 1~8。
6. 对每个 Milestone 列出：
   - 目标；
   - 新增/修改文件；
   - 验收标准；
   - 风险。
7. 如果当前仓库为空，从 Milestone 1 开始搭建。
8. 每完成一个 Milestone 后停止，并等待下一步指令，不要擅自一次完成全部项目。

---

# 30. 后续学习模式

项目完成后，不再让 Codex 继续堆功能，而切换为学习模式。

提示词：

```text
现在停止开发模式，进入“项目解构导师模式”。

目标不是继续修改代码，而是帮助我面向 Java 后端 / AI 应用开发面试，自顶向下理解当前 VideoAgent 项目。

规则：
1. 从一次完整用户请求链路开始讲。
2. 每次只深入一个模块。
3. 先讲它解决的业务问题，再讲代码实现。
4. 然后解释为什么选择当前技术。
5. 给出至少一种替代方案，并比较优缺点。
6. 对 Redis、RocketMQ、MySQL、Spring Boot 等核心技术主动追问底层原理。
7. 不要因为我的回答听起来合理就直接肯定；必须判断是否准确，有问题直接指出。
8. 每完成一个模块，对我进行 3~5 个面试追问。
9. 如果我回答不完整，继续追问，直到我真正能够解释。
10. 不要求我深入 ASR 模型算法；ASR 只考察工程接入、输入输出、超时、重试与异步设计。
```

---

# 31. 最终原则

这个项目不是为了证明：

> “我会使用最多的技术。”

而是为了做到：

> “我能从一个真实业务链路出发，解释为什么需要这些技术，以及这些技术如何协同解决问题。”

始终遵守：

```text
问题 -> 技术选择 -> 实现 -> 故障场景 -> 替代方案 -> 面试表达
```

而不是：

```text
技术名词 -> 强行塞进项目
```
