# Milestone 4 验收报告

验收日期：2026-08-08（Asia/Shanghai）

验收结论：**PASS**

里程碑：**Milestone 4 — FFmpeg + Mock ASR**

## 1. 范围结论

Milestone 4 已完成代码、构建、真实基础设施和浏览器验收。M3 的 sleep 模拟处理已替换为以下真实媒体链路：

```text
MinIO Video
  -> 受控临时视频文件
  -> FFmpeg 提取 16 kHz / mono / PCM WAV
  -> MockAsrProvider
  -> timestamp transcript segments
  -> MySQL video_transcript_segment
  -> AnalysisTask SUCCESS / DONE / 100
```

M3 的 RocketMQ 异步边界、Redis 实时进度、MySQL 持久状态、PENDING 原子抢占、SUCCESS 重复消费短路和查询回退均保留。

本阶段未实现真实 ASR、Whisper、LLM、LangChain4j、Summary、Chapter、Key Point、RAG、Embedding、SSE、WebSocket、OCR、VLM、Speaker Diarization、GPU 推理、Transactional Outbox、DLQ 扩展、多 Topic 流水线、分片上传或断点续传。未进入 Milestone 5。

## 2. Git 与秘密文件

- 分支：`main`。
- Milestone 3 已按要求创建独立检查点：
  - `5e1e058 feat: complete milestone 3 async analysis framework`
- 历史检查点：
  - `25b5a7b feat: complete milestone 2 video upload`
  - `b2e0fe4 feat: complete milestone 1 infrastructure`
- `git check-ignore -v .env` 命中 `.gitignore` 第 2 行。
- `git ls-files --error-unmatch .env` 返回未匹配，确认 `.env` 未被跟踪。
- `.env.example` 只包含 `change-me-*` 占位值和空 API Key，没有真实密钥。
- Milestone 4 当前改动未提交，等待后续独立检查点指令。
- 当前另有未跟踪的 `AI_HANDOFF.md`；本阶段未修改、删除或纳入 M4 文件清单。

## 3. 数据库设计

Flyway migration：`V3__create_video_transcript_segment_table.sql`。

| 字段 | 设计 |
| --- | --- |
| `id` | `BIGINT UNSIGNED` 自增主键 |
| `video_id` | 视频外键，`ON DELETE RESTRICT` |
| `task_id` | 分析任务外键，`ON DELETE CASCADE` |
| `segment_index` | 任务内从 0 开始的稳定片段序号 |
| `start_ms/end_ms` | 毫秒时间范围，CHECK 保证 `end_ms > start_ms` |
| `text` | 单片段 `VARCHAR(2000)`，CHECK 禁止空白文本 |
| `created_at` | 毫秒精度创建时间 |

约束和索引：

- `UNIQUE(task_id, segment_index)`：防止同一任务重复写入同序号片段。
- `INDEX(video_id, segment_index)`：支持视频字幕按业务序号读取。
- `INDEX(video_id, start_ms)`：支持按视频时间轴定位。
- 字幕分片保存，不使用单个超大 `LONGTEXT`。
- API 只返回最新 SUCCESS 任务的字幕，并按 `segment_index, start_ms, id` 排序。

真实 MySQL 已由 Flyway 从 schema v2 升级到 v3，并成功验证 3 个 migration。

## 4. MediaProcessor 边界

`MediaProcessor` 只定义视频到音频：

```java
AudioExtractResult extractAudio(Path videoFile, Path audioFile);
```

`FfmpegMediaProcessor`：

- 使用 `ProcessBuilder(List<String>)`，不经过 shell，不拼接用户输入命令。
- 固定参数提取 `mono / 16 kHz / pcm_s16le` WAV。
- 配置执行超时；超时后先 terminate，再必要时 forcibly terminate。
- 非 0 exit code、超时、输出缺失分别映射为明确错误码。
- stderr 写入工作区临时文件，读取时按配置截断，随后清理。
- 输入必须是非符号链接的普通文件；输出必须与输入位于同一任务工作区且不能覆盖输入。
- 不包含视频剪辑、视频生成或复杂转码能力。

## 5. ObjectStorage 与临时文件生命周期

现有 `ObjectStorageService` 只做最小扩展：

```java
void downloadObject(String objectKey, Path destination);
```

MinIO SDK 调用仍集中在 `MinioStorageService`，Consumer 不直接依赖 MinIO SDK。

每个任务的临时文件生命周期：

```text
配置的 temp root
  -> analysis-{taskId}-{random}/
       -> source.mp4
       -> audio.wav
       -> ffmpeg-*.stderr
  -> try-with-resources 退出时反向递归清理任务目录
```

清理逻辑验证每个删除目标都在任务工作区内。真实成功、真实 FFmpeg 失败和组件测试结束后，临时根目录剩余任务项均为 0。

## 6. AsrProvider 边界

`AsrProvider` 接收 `AudioSource`，返回 `TranscriptionResult`；结果由多个 `TranscriptSegment(startMs, endMs, text)` 组成。

M4 只实现 `MockAsrProvider`：

- 校验音频是非空、非符号链接普通文件。
- 返回 3 个确定性的时间戳片段：`0–2000`、`2000–4000`、`4000–6000` ms。
- 不需要第三方 API Key，可重复运行完整链路。
- 未接入真实 ASR Provider、模型部署或训练。

## 7. 分析版本与 Consumer 流程

M4 使用新的业务键：

```text
analysis_type = TRANSCRIPTION
model_version = m4-ffmpeg-mock-asr-v1
```

理由：M3 历史键 `FRAMEWORK / m3-simulation-v1` 保持不变；`UNIQUE(video_id, analysis_type, model_version)` 允许同一已有视频额外创建一个 M4 转录任务。真实 M4 基础设施测试在同一视频上先插入 M3 SUCCESS 历史任务，再成功创建并完成 M4 任务，最后断言 M3 记录未改变。

M4 Consumer 阶段：

| 进度 | 阶段 | 持久化/实时行为 |
| --- | --- | --- |
| 10 | `PREPARING` | MySQL 原子抢占 PENDING，Redis 发布处理中 |
| 35 | `EXTRACTING_AUDIO` | MinIO 视频已落临时文件，开始 FFmpeg |
| 70 | `TRANSCRIBING` | Mock ASR 生成片段 |
| 90 | `SAVING` | 事务性替换当前 task 的字幕片段 |
| 100 | `DONE` | MySQL SUCCESS，Redis SUCCESS |

Consumer 只处理当前配置的 M4 类型/版本；已完成 SUCCESS 消息再次消费时直接返回，字幕和 `updated_at` 均不变化。

## 8. API 与前端

新增接口：

```text
GET /api/videos/{videoId}/transcript
```

结果：

- 视频不存在：404 `VIDEO_NOT_FOUND`。
- 视频存在但尚无成功字幕：200 和空数组。
- 有成功任务：按片段序号/时间返回 `startMs/endMs/text` DTO。

前端 `VideoDetailView`：

- 保留普通 HTTP 轮询。
- 显示提取音频、转录、保存、完成或失败状态。
- SUCCESS 后加载 transcript，显示 `00:00 / 00:02 / 00:04` 和字幕文本。
- 页面刷新后从持久化字幕恢复完成态，按钮保持禁用并显示“分析已完成”。
- 当前没有视频播放器，因此时间戳只展示，不提前增加代理或下载架构。

浏览器自动化无法为 Element Plus 隐藏的本地 file input 注入 Windows 文件；M4 验收 MP4 通过同一 multipart API 上传。上传页本身已在浏览器中实际渲染，M2 已验收的上传代码未被修改；M4 新增的“发起分析 → 轮询 → 展示字幕 → 刷新恢复”全部通过浏览器实际点击验证。

## 9. 实际执行的关键命令

Git 与秘密检查：

```powershell
git status --short --branch
git check-ignore -v .env
git ls-files --error-unmatch .env
git add .
git commit -m "feat: complete milestone 3 async analysis framework"
git diff --check
```

基础设施与 FFmpeg：

```powershell
docker compose ps
curl.exe --fail --location --output tmp/ffmpeg-9.0-essentials_build.7z <official-listed-mirror>
Get-FileHash -Algorithm SHA256 tmp/ffmpeg-9.0-essentials_build.7z
tar.exe -xf tmp/ffmpeg-9.0-essentials_build.7z -C tmp/ffmpeg-local
tmp/ffmpeg-local/ffmpeg-9.0-essentials_build/bin/ffmpeg.exe -version
```

FFmpeg 9.0 归档实测 SHA-256：

```text
ffb866303866995734849995027533b9756971215e8c55ef408073628cdc27a2
```

后端测试：

```powershell
Set-Location backend
mvn test

$env:VIDEOAGENT_FFMPEG_TEST = 'true'
$env:FFMPEG_PATH = '<absolute-ffmpeg.exe>'
mvn '-Dtest=FfmpegMediaProcessorTest' test

$env:VIDEOAGENT_M4_INFRA_TEST = 'true'
mvn '-Dtest=MediaTranscriptionInfrastructureIntegrationTest' test

$env:VIDEOAGENT_M3_INFRA_TEST = 'true'
mvn '-Dtest=AnalysisFrameworkInfrastructureIntegrationTest' test
mvn spring-boot:run
```

前端与真实探针：

```powershell
Set-Location frontend
npm run build
npm run dev -- --host 127.0.0.1

curl.exe -F "file=@tmp/milestone4-browser.mp4;type=video/mp4" `
  -F "title=M4 Browser Acceptance" http://localhost:8080/api/videos
curl.exe http://localhost:8080/api/analysis/12
docker compose exec -T redis redis-cli GET video:analysis:progress:12
docker compose exec -T redis redis-cli DEL video:analysis:progress:12
docker compose exec -T mysql mysql <credentials-from-untracked-env> --execute=<read-only-select>
docker compose exec -T minio ls -l /data/videoagent/videos/2026/08/08
```

## 10. 验证结果

### 10.1 构建与自动化测试

| 项目 | 结果 |
| --- | --- |
| 后端完整测试 | PASS，43 tests，0 failures，0 errors，9 skipped |
| FFmpeg 真实组件测试 | PASS，3/3：成功、非 0 exit、超时 |
| M4 真实基础设施测试 | PASS，2/2，无跳过 |
| M3 真实基础设施回归 | PASS，2/2，无跳过 |
| 前端 TypeScript + Vite 构建 | PASS，1677 modules transformed |
| `git diff --check` | PASS |

普通 `mvn test` 的 9 个 skipped 是显式环境变量门控的真实基础设施/本机 FFmpeg 测试。其中与 M4 和 M3 回归直接相关的 7 项已按上表分别启用并实际执行；其余 2 项是已在 M1/M2 独立验收通过的基础设施测试，本次未重复开启。

### 10.2 必测场景

| 验收项 | 结果 |
| --- | --- |
| FFmpeg 成功提取音频 | PASS，真实 FFmpeg 组件测试与 M4 E2E 均执行 |
| FFmpeg 失败使 task FAILED | PASS，无效 MP4 得到 `FFMPEG_EXECUTION_FAILED` |
| Mock ASR 返回 timestamp segments | PASS，确定性 3 段 |
| transcript segments 入库 | PASS，task/video/序号/时间/文本均断言 |
| transcript API 顺序 | PASS，`0 / 2000 / 4000` ms |
| SUCCESS 重复消费跳过 | PASS，`updated_at` 不变且仍为 3 段 |
| Redis 丢失后 MySQL 回退 | PASS，删除 key 后 GET 仍为 SUCCESS/100 |
| 临时媒体文件清理 | PASS，成功和失败路径均为 0 个残留任务目录 |
| M3 测试不回归 | PASS，M3 真实基础设施测试 2/2 |
| M3 历史版本与 M4 共存 | PASS，同 video 同时保留两种业务键 |

### 10.3 保留的真实验收记录

- Video：`id=18`，标题 `M4 Browser Acceptance`，文件大小 `42422` bytes。
- MinIO object key：`videos/2026/08/08/aad0f9dc-ddc3-4ce0-baf6-81c5ec280da1.mp4`，数据目录中实际存在。
- Analysis task：`id=12`。
- MySQL：`TRANSCRIPTION / m4-ffmpeg-mock-asr-v1 / SUCCESS / DONE / 100`。
- Transcript：3 行，`segment_index=0/1/2`，`start_ms=0/2000/4000`。
- Redis 最终值曾实测为 `SUCCESS / DONE / 100 / 分析完成`；随后为回退验收主动删除该 key。
- 删除 Redis key 后，`GET /api/analysis/12` 仍返回 SUCCESS、DONE、100 及完整生命周期时间。
- 系统临时根目录 `videoagent-media` 实测剩余项为 0。

### 10.4 浏览器验收

在 `http://127.0.0.1:5173/videos/18` 实际点击“开始 AI 分析”：

- HTTP 请求返回 task #12，前端进入轮询。
- 后台链路完成后页面显示“已完成 / 分析完成 / 100%”。
- Transcript 区显示 3 个片段：`00:00`、`00:02`、`00:04`。
- 刷新详情页后，字幕仍从 MySQL 返回，按钮显示“分析已完成”。
- 验收中发现并修复刷新后的文案矛盾：已有字幕时不再显示“尚未创建分析任务”。

## 11. 新增/修改文件

配置与文档：

- `.env.example`
- `README.md`
- `IMPLEMENTATION_PLAN.md`
- `MILESTONE_4_ACCEPTANCE_REPORT.md`
- `backend/src/main/resources/application.yml`

数据库、存储与公共异常：

- `backend/src/main/resources/db/migration/V3__create_video_transcript_segment_table.sql`
- `backend/src/main/java/com/videoagent/storage/ObjectStorageService.java`
- `backend/src/main/java/com/videoagent/storage/MinioStorageService.java`
- `backend/src/main/java/com/videoagent/common/exception/ErrorCode.java`

后端 Media 与 ASR：

- `backend/src/main/java/com/videoagent/media/AudioExtractResult.java`
- `backend/src/main/java/com/videoagent/media/MediaProcessor.java`
- `backend/src/main/java/com/videoagent/media/FfmpegMediaProcessor.java`
- `backend/src/main/java/com/videoagent/media/MediaProperties.java`
- `backend/src/main/java/com/videoagent/media/MediaWorkspace.java`
- `backend/src/main/java/com/videoagent/media/TemporaryMediaWorkspace.java`
- `backend/src/main/java/com/videoagent/asr/AudioSource.java`
- `backend/src/main/java/com/videoagent/asr/AsrProvider.java`
- `backend/src/main/java/com/videoagent/asr/MockAsrProvider.java`
- `backend/src/main/java/com/videoagent/asr/TranscriptionResult.java`
- `backend/src/main/java/com/videoagent/asr/TranscriptSegment.java`

后端 Transcript 与 Analysis：

- `backend/src/main/java/com/videoagent/transcript/controller/TranscriptController.java`
- `backend/src/main/java/com/videoagent/transcript/dto/TranscriptSegmentResponse.java`
- `backend/src/main/java/com/videoagent/transcript/entity/VideoTranscriptSegmentEntity.java`
- `backend/src/main/java/com/videoagent/transcript/repository/VideoTranscriptSegmentRepository.java`
- `backend/src/main/java/com/videoagent/transcript/service/TranscriptService.java`
- `backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java`
- `backend/src/main/java/com/videoagent/analysis/entity/AnalysisStage.java`
- `backend/src/main/java/com/videoagent/analysis/service/AnalysisProperties.java`

后端测试：

- `backend/src/test/java/com/videoagent/media/FfmpegMediaProcessorTest.java`
- `backend/src/test/java/com/videoagent/media/TemporaryMediaWorkspaceTest.java`
- `backend/src/test/java/com/videoagent/asr/MockAsrProviderTest.java`
- `backend/src/test/java/com/videoagent/transcript/controller/TranscriptControllerTest.java`
- `backend/src/test/java/com/videoagent/transcript/service/TranscriptServiceTest.java`
- `backend/src/test/java/com/videoagent/analysis/MediaTranscriptionInfrastructureIntegrationTest.java`
- `backend/src/test/java/com/videoagent/analysis/AnalysisFrameworkInfrastructureIntegrationTest.java`
- `backend/src/test/java/com/videoagent/analysis/consumer/AnalysisTaskProcessorTest.java`
- M3 受构造参数影响的既有测试与 health bean mock。

前端：

- `frontend/src/services/transcript.ts`
- `frontend/src/types/transcript.ts`
- `frontend/src/types/analysis.ts`
- `frontend/src/views/VideoDetailView.vue`
- `frontend/src/styles/main.css`
- `frontend/src/App.vue`

## 12. 当前目录结构

```text
Video agent/
├── backend/
│   ├── src/main/java/com/videoagent/
│   │   ├── analysis/
│   │   ├── asr/
│   │   ├── common/
│   │   ├── media/
│   │   ├── storage/
│   │   ├── transcript/
│   │   └── video/
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_video_table.sql
│   │   ├── V2__create_analysis_task_table.sql
│   │   └── V3__create_video_transcript_segment_table.sql
│   └── src/test/java/com/videoagent/
│       ├── analysis/
│       ├── asr/
│       ├── common/
│       ├── media/
│       ├── transcript/
│       └── video/
├── frontend/src/
│   ├── services/{api,video,analysis,transcript}.ts
│   ├── types/{video,analysis,transcript}.ts
│   ├── views/{VideoListView,UploadView,VideoDetailView}.vue
│   ├── router/
│   └── styles/
├── infra/rocketmq/
├── docker-compose.yml
├── .env.example
├── IMPLEMENTATION_PLAN.md
├── MILESTONE_1_ACCEPTANCE_REPORT.md
├── MILESTONE_2_ACCEPTANCE_REPORT.md
├── MILESTONE_3_ACCEPTANCE_REPORT.md
├── MILESTONE_4_ACCEPTANCE_REPORT.md
├── README.md
└── VideoAgent_Codex_Spec.md
```

## 13. 已知问题与边界

- 本机全局 `PATH` 没有 FFmpeg；验收使用 `tmp/` 下经 SHA-256 校验的 FFmpeg 9.0 本地构建。该目录被 `.gitignore` 排除，不会提交。后续运行需安装 FFmpeg 或配置 `FFMPEG_PATH`。
- Mock ASR 返回固定 3 段内容，不读取真实语义；它仅用于验证工程链路。
- 页面没有视频播放元素，时间戳按要求只展示。
- 页面刷新能从字幕判断已完成，但当前 API 不提供“按视频查询任务历史”，因此不会恢复已结束 taskId 的进度卡片。
- DB 事务与 MQ 发送不是原子提交，沿用 M3 已记录的一致性边界；本阶段未实现 Transactional Outbox。
- Flyway 对 MySQL 8.4 支持范围、Commons Logging、Mockito 动态 Agent 和 Vite 大 bundle 存在非阻断告警。
- 自动化浏览器的 Windows 本地文件选择限制不属于产品代码错误；M2 上传功能未回归，M4 页面新增链路已实际浏览器验收。

## 14. 最终结论

**Milestone 4：PASS。**

当前实现满足进入 Milestone 5 的技术前置条件，但本次严格停止在 Milestone 4。未开始任何 Milestone 5 功能，等待用户后续明确指令。
