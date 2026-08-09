# Milestone 6.5 — Real AI Integration 验收报告

验收日期：2026-08-09  
验收范围：Groq Speech-to-Text、DeepSeek OpenAI-compatible LLM、无音轨错误分类、M1–M6 回归  
基线提交：`e2d1b98 feat: complete milestone 6 sse realtime progress`

> 更新说明：第 1–8 节保留首次 Groq Provider 实现与验收历史。当前推荐 Real ASR、最终验证状态及对这些历史配置的替代关系见第 9 节 **Provider Replacement / Real AI Final Verification**。

## 1. 结论

| 项目 | 结果 |
| --- | --- |
| M6.5 代码实现与自动化测试 | **PASS** |
| Mock Provider 默认开发/CI 链路 | **PASS** |
| 真实 MySQL / Redis / MinIO / RocketMQ / FFmpeg / SSE 回归 | **PASS** |
| 前端类型检查与生产构建 | **PASS** |
| DashScope + DeepSeek 真实浏览器 smoke test | **PASS（用户本地执行）** |
| 总体结论 | **PASS**：离线自动化回归与用户本地真实 AI 链路均已验证 |

未将 Mock 结果描述为真实 AI 结果。当前环境中：

- `VIDEOAGENT_REAL_AI_TEST` 未开启；
- `ASR_API_KEY` 不存在；
- `LLM_API_KEY` 不存在；
- `VIDEOAGENT_REAL_AI_VIDEO` 不存在。

因此没有产生 Groq 或 DeepSeek 费用，也无法声称真实 Transcript 与真实视频语音一致。

## 2. 实现结果

### 2.1 ASR Provider

保留既有边界：

```text
AnalysisTaskProcessor
        |
        v
   AsrProvider
    /       \
MockAsr   GroqAsr
```

- 默认 `ASR_PROVIDER=mock`，普通开发、CI 和自动化测试不访问外网。
- `ASR_PROVIDER=groq` 时使用 `GroqAsrProvider`。
- Groq 请求为 `multipart/form-data`：
  - `file=<audio.wav>`
  - `model=whisper-large-v3-turbo`
  - `response_format=verbose_json`
  - `timestamp_granularities[]=segment`
  - `language=zh`
- 鉴权仅从 `ASR_API_KEY` 读取并设置 Bearer Header。
- `segments[].start/end` 使用 `Math.round(seconds * 1000)` 映射为毫秒。
- 没有引入 Groq SDK，也没有在 Consumer 中增加厂商分支。

实现依据：[Groq Speech-to-Text 官方文档](https://console.groq.com/docs/speech-to-text)。

### 2.2 ASR 结果校验

真实响应持久化前验证：

- segments 非空；
- segment 与 text 非空；
- `startMs >= 0`；
- `endMs > startMs`；
- start/end 时间单调不倒退；
- 使用 Java Audio API 读取 FFmpeg PCM WAV 时长；
- segment 不得超过音频时长加 1500ms 容差；
- 不合法结果统一映射为 `ASR_RESPONSE_INVALID`，不会写入 MySQL。

外部调用错误分类：

- `ASR_REQUEST_FAILED`
- `ASR_TIMEOUT`
- `ASR_RESPONSE_INVALID`

Provider 不记录请求 Header、API Key 或第三方响应正文；持久错误消息使用受控文本。

### 2.3 无音轨视频

采用最小 FFmpeg 方案，没有引入媒体探测框架：

- 命令显式增加 `-map 0:a:0`，选择第一条音轨；
- 根据 FFmpeg 的稳定 stderr 特征分类无匹配音频流；
- 返回错误码 `VIDEO_AUDIO_STREAM_NOT_FOUND`；
- 用户消息固定为：`该视频不包含可用于语音转写的音轨`；
- 其他非法媒体仍保持 `FFMPEG_EXECUTION_FAILED`。

真实 FFmpeg 测试已生成一个只有视频流的 MP4，确认错误码和消息正确。

### 2.4 DeepSeek LLM

没有新增 DeepSeek 专用业务层，继续使用：

```text
VideoSummaryProvider
        |
LangChain4jVideoSummaryProvider
        |
LangChain4j OpenAI-compatible OpenAiChatModel
```

推荐真实模式配置：

```text
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-flash
LLM_STRUCTURED_OUTPUT_MODE=json_object
```

`LLM_API_KEY` 仅从环境变量读取。原有 `json_schema` 模式继续保留；新增通用模式：

- `json_schema`：保留 M5 严格 JSON Schema 行为；
- `json_object`：适配 DeepSeek 文档化的 JSON Output；
- `prompting`：保留 LangChain4j AI Services 的结构提示回退。

本地 HTTP 合约测试实际确认：

- 请求模型为 `deepseek-v4-flash`；
- `response_format.type=json_object`；
- 请求仍由通用 OpenAI-compatible Provider 发出；
- 返回结构可解析并通过既有 `SummaryResultValidator`。

实现依据：[DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[DeepSeek Chat Completion API](https://api-docs.deepseek.com/api/create-chat-completion)、[LangChain4j Structured Outputs](https://docs.langchain4j.dev/tutorials/structured-outputs/)。

### 2.5 真实 AI smoke test

新增 `RealAiInfrastructureSmokeTest`，默认跳过。只有以下条件全部满足才运行：

- `VIDEOAGENT_REAL_AI_TEST=true`
- `ASR_API_KEY` 非空
- `LLM_API_KEY` 非空
- `VIDEOAGENT_REAL_AI_VIDEO` 指向存在的 MP4
- `VIDEOAGENT_REAL_AI_EXPECTED_TEXT` 非空

测试链路：

```text
POST video upload
  -> MinIO
  -> POST analysis
  -> RocketMQ Consumer
  -> FFmpeg
  -> Groq ASR
  -> timestamp transcript
  -> DeepSeek structured summary
  -> MySQL / Redis
  -> transcript / summary / chapters / key-points APIs
```

测试还会比较规范化后的 Transcript 与 `VIDEOAGENT_REAL_AI_EXPECTED_TEXT`，防止仅凭非空结果误判为理解了真实语音。LLM retry 在 smoke test 中固定为 0，等待上限 3 分钟，只处理一个短视频。

## 3. 自动化与回归结果

### 3.1 后端完整默认测试

结果：

```text
Tests run: 79
Failures: 0
Errors: 0
Skipped: 13
```

13 个 skipped 包含需要显式基础设施开关的历史测试及 `RealAiInfrastructureSmokeTest`。默认测试没有访问 Groq、DeepSeek 或任何付费 API。

新增覆盖：

- Mock/Groq Provider 选择；
- Groq multipart 字段与 Bearer Header；
- 秒到毫秒的 round 映射；
- 非空、单调性、音频时长越界校验；
- malformed JSON；
- HTTP failure；
- timeout；
- Provider 错误消息不泄露外部响应内容；
- DeepSeek OpenAI-compatible JSON Object 请求与结构解析；
- 真实 AI 测试门控。

### 3.2 FFmpeg 实际测试

使用已在仓库 `tmp/` 中验收过的 FFmpeg 9.0 本地构建，执行 `FfmpegMediaProcessorTest`：

```text
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0
```

覆盖：音频提取成功、非法媒体、执行超时、无音轨 MP4 分类。

### 3.3 真实基础设施与 SSE 回归

`docker compose ps`：

| 服务 | 状态 |
| --- | --- |
| MySQL | Up / healthy |
| Redis | Up / healthy |
| MinIO | Up / healthy |
| RocketMQ NameServer | Up / healthy |
| RocketMQ Broker | Up / healthy |

执行 `AnalysisSseInfrastructureIntegrationTest`，结果 PASS。实际链路包含：

- HTTP 上传；
- MinIO 对象；
- MySQL video / task / transcript / summary 数据；
- RocketMQ Producer / Consumer；
- 真实 FFmpeg；
- Mock ASR / Mock Summary；
- Redis progress；
- SSE progress 与终态；
- Transcript / Summary / Chapters / Key Points；
- 临时文件清理。

日志确认本次任务达到 `DONE / SUCCESS`。RocketMQ 中存在历史测试消息，Consumer 按既有幂等规则跳过 SUCCESS/FAILED 或已清理任务，不影响本次任务。

### 3.4 前端

前端没有业务代码修改。执行：

- Vue TypeScript application type-check：PASS；
- Vite config TypeScript type-check：PASS；
- Vite production build：PASS；
- 1679 modules transformed；
- `VideoDetailView`、上传页和视频列表页均成功产出 chunk。

构建只有既有大 chunk 提示，没有编译错误。

## 4. 实际执行过的主要命令

```powershell
git status --short
git check-ignore -v .env
git ls-files -- .env
git add .
git commit -m "feat: complete milestone 6 sse realtime progress"

docker compose ps

mvn -q -DskipTests compile
mvn -q test
$env:VIDEOAGENT_FFMPEG_TEST='true'
$env:FFMPEG_PATH='D:\Vibe Coding\Video agent\tmp\ffmpeg-local\ffmpeg-9.0-essentials_build\bin\ffmpeg.exe'
mvn -q -Dtest=FfmpegMediaProcessorTest test
$env:VIDEOAGENT_M6_INFRA_TEST='true'
mvn -q -Dtest=AnalysisSseInfrastructureIntegrationTest test

.\node_modules\.bin\vue-tsc.cmd --noEmit -p tsconfig.app.json --incremental false
.\node_modules\.bin\tsc.cmd --noEmit -p tsconfig.node.json --incremental false
.\node_modules\.bin\vite.cmd build --configLoader runner --outDir ..\tmp\codex-m65-frontend-dist --emptyOutDir

git diff --check
```

说明：仓库现有 `backend/target/classes/application.yml` 和 `frontend/node_modules/.tmp` 在当前 Windows 会话中被拒绝覆盖。后端验收使用位于仓库 `tmp/` 的等价源码构建副本，并显式复用 `backend/.m2-repository`；前端通过关闭 incremental build-info 写入并使用 Vite `runner` config loader 完成构建。所有本轮 `codex-m65-*` 临时目录已清理。

## 5. 修改/新增文件

### 修改

- `.env.example`
- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/videoagent/asr/MockAsrProvider.java`
- `backend/src/main/java/com/videoagent/common/exception/ErrorCode.java`
- `backend/src/main/java/com/videoagent/media/FfmpegMediaProcessor.java`
- `backend/src/main/java/com/videoagent/summary/provider/LangChain4jSummaryAiService.java`
- `backend/src/main/java/com/videoagent/summary/provider/SummaryProviderConfiguration.java`
- `backend/src/main/java/com/videoagent/summary/provider/SummaryProviderProperties.java`
- `backend/src/test/java/com/videoagent/media/FfmpegMediaProcessorTest.java`
- `backend/src/test/java/com/videoagent/summary/provider/SummaryProviderConfigurationTest.java`

### 新增

- `backend/src/main/java/com/videoagent/asr/AsrProviderConfiguration.java`
- `backend/src/main/java/com/videoagent/asr/AsrProviderProperties.java`
- `backend/src/main/java/com/videoagent/asr/AsrResultValidator.java`
- `backend/src/main/java/com/videoagent/asr/GroqAsrProvider.java`
- `backend/src/test/java/com/videoagent/asr/AsrProviderConfigurationTest.java`
- `backend/src/test/java/com/videoagent/asr/GroqAsrProviderTest.java`
- `backend/src/test/java/com/videoagent/summary/provider/DeepSeekOpenAiCompatibleProviderTest.java`
- `backend/src/test/java/com/videoagent/analysis/RealAiInfrastructureSmokeTest.java`
- `MILESTONE_6_5_ACCEPTANCE_REPORT.md`

## 6. Secret 检查

- `.gitignore` 明确忽略 `.env`；
- `git ls-files -- .env` 无输出；
- `.env tracked=false`；
- `.env.example` 只有空值和非秘密配置；
- 源码、测试和本报告不包含真实 Groq/DeepSeek API Key；
- 未在日志中输出 API Key。

## 7. 已知问题与后续动作

1. **真实 AI 自动化 smoke test 默认仍会跳过。** 用户已通过浏览器完成同等真实链路验收；默认 `mvn test` 继续不访问外部收费 API。
2. 本机全局 `PATH` 未配置 FFmpeg；当前验收使用 M4 已下载并校验的本地 FFmpeg。日常运行需设置 `FFMPEG_PATH` 或安装 FFmpeg。
3. 当前 Windows 会话中的既有 Maven/TypeScript build-info 文件存在覆盖权限问题；使用隔离构建目录验证通过，源码本身无编译问题。
4. Vite 仍提示主 chunk 超过 500kB，这是 M1–M6 已存在的非阻断构建提示，本阶段未做范围外的前端拆包。

## 8. 范围确认

本阶段没有加入：RAG、Embedding、Vector DB、Agent、Tool Calling、OCR、VLM、Whisper 自部署、GPU 推理、Redis Pub/Sub、Transactional Outbox、DLQ 扩展、微服务或 Kubernetes。

未开始 Milestone 7。

## 9. Provider Replacement / Real AI Final Verification

### 9.1 替换原因与边界

用户在真实网络环境中直接使用 curl 验证：

- `GET /openai/v1/models`：SUCCESS；
- `POST /openai/v1/chat/completions`：SUCCESS；
- `POST /openai/v1/audio/transcriptions` + MP4：HTTP 403；
- `POST /openai/v1/audio/transcriptions` + FFmpeg WAV：HTTP 403。

该现象客观记录为 **Groq Audio endpoint 在用户环境中不可访问**。没有猜测 HTTP 403 根因，也没有将其归因于 Java multipart 或 FFmpeg。

保留以下历史实现和离线测试：

- `GroqAsrProvider`
- `GroqAsrProviderTest`

新增并作为推荐真实 Provider：

```text
AsrProvider
├── MockAsrProvider       默认开发 / CI
├── GroqAsrProvider       保留的可选 Provider
└── DashScopeAsrProvider  推荐真实本地演示
```

没有修改 RocketMQ、`AnalysisTaskProcessor`、Redis、MySQL、SSE、FFmpeg、Transcript 数据模型、DeepSeek Provider 或前端。

### 9.2 DashScope 官方协议实现

依据当前[阿里云 Fun-ASR-Flash API 官方参考](https://help.aliyun.com/zh/model-studio/non-real-time-speech-recognition-for-fun-asr-flash)：

- 模型：`fun-asr-flash-2026-06-15`；
- 地域：中国华北2（北京）；
- 默认通用 endpoint：`https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`；
- 官方推荐的 Workspace-specific endpoint 可通过 `ASR_BASE_URL` 完整配置，代码没有硬编码 Workspace ID；
- Authentication：`Authorization: Bearer ${ASR_API_KEY}`；
- `Content-Type: application/json`；
- `X-DashScope-SSE: enable`；
- WAV 转为 `data:audio/wav;base64,...` 后写入 `input.messages[].content[].input_audio.data`；
- `parameters.format="wav"`；
- `parameters.sample_rate="16000"`；
- `parameters.language_hints=["zh"]`。

使用 Base64 是因为本地 MinIO 和 Docker network 不可由阿里云直接访问；没有引入 OSS、公网 MinIO、presigned public hosting 或上传中转服务。Provider 在读取文件前计算 Base64 后长度，超过官方 10MB Data URI 输入限制时返回受控错误 `ASR_INPUT_TOO_LARGE`。

### 9.3 SSE timestamp transcript

Provider 逐事件解析 SSE：

1. 只处理 `event:result`；
2. 解析 `data` JSON；
3. 只接受 `output.sentence.sentence_end=true`；
4. 非 final sentence 直接忽略；
5. `begin_time` 直接映射为 `startMs`；
6. `end_time` 直接映射为 `endMs`；
7. 时间值已经是毫秒，不乘以 1000；
8. 最终继续经过既有 `AsrResultValidator` 校验非空文本、时间合法性、单调性和 WAV 时长边界。

没有实现 VAD、语义切句、词级 timestamp 聚合或长音频分片。

### 9.4 推荐真实配置

```text
ASR_PROVIDER=dashscope
ASR_API_KEY=
ASR_MODEL=fun-asr-flash-2026-06-15
ASR_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
ASR_TIMEOUT=60s

LLM_PROVIDER=openai
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-flash
LLM_STRUCTURED_OUTPUT_MODE=json_object
```

`.env.example` 仍默认 `ASR_PROVIDER=mock`，因此普通启动和自动化测试不会调用 Alibaba、Groq 或 DeepSeek。

### 9.5 Real AI smoke test 更新

`RealAiInfrastructureSmokeTest` 的真实 ASR 已从 Groq 切换为 DashScope：

```text
MP4
  -> MinIO
  -> RocketMQ
  -> FFmpeg 16kHz mono PCM WAV
  -> DashScope Fun-ASR-Flash SSE
  -> timestamp Transcript
  -> DeepSeek structured summary
  -> MySQL / Redis
  -> REST result verification
```

门控条件保持不变：

- `VIDEOAGENT_REAL_AI_TEST=true`
- `ASR_API_KEY`
- `LLM_API_KEY`
- `VIDEOAGENT_REAL_AI_VIDEO`
- `VIDEOAGENT_REAL_AI_EXPECTED_TEXT`

当前没有使用真实 Alibaba 或 DeepSeek API Key，也没有执行收费请求。因此：

| 验收项 | 结果 |
| --- | --- |
| DashScope Provider 实现 | PASS |
| DashScope 本地 HTTP/SSE contract tests | PASS |
| Groq / Mock 回归 | PASS |
| DeepSeek OpenAI-compatible 离线 contract test | PASS |
| 默认完整测试 | PASS：86 tests / 0 failures / 0 errors / 13 skipped |
| 真实 FFmpeg + Docker M6 SSE Mock 回归 | PASS：5 tests / 0 failures / 0 errors / 0 skipped |
| DashScope + DeepSeek 真实浏览器 smoke test | **PASS（用户本地执行）** |
| M6.5 最终状态 | **PASS** |

用户已在本地浏览器完成真实链路验证：真实视频经 DashScope 生成 timestamp transcript，DeepSeek 基于真实 transcript 生成 Overview、Chapters 与 Key Points，结果通过 SSE 和现有前端正常展示。API Key 与真实 token 未提供给代码代理，也未写入源码、日志、测试或本报告。

真实验证后补充了 `LangChain4jSummaryAiService` 的最小 Prompt 约束：所有面向用户的 overview、chapter title/summary 与 key point content 默认使用简体中文，专有名词、技术名称、产品名和代码标识符可保留原文；JSON 字段名、Structured Output、Validator 与 Provider 架构均未改变，也没有增加第二次翻译调用。对应 DeepSeek OpenAI-compatible 契约测试已验证该语言约束进入实际请求 messages。

### 9.6 DashScope 新增测试覆盖

`DashScopeAsrProviderTest` 使用本地 HTTP server，不访问收费 API，覆盖：

- Provider selection；
- Bearer Header；
- Base64 WAV Data URI 构造及原始 WAV 字节还原；
- model；
- `format=wav`；
- `sample_rate=16000`；
- `language_hints=zh`；
- SSE final sentence 解析；
- begin/end 毫秒直接映射；
- 非 final sentence 不进入结果；
- 无 final sentence；
- malformed SSE JSON；
- HTTP 403；
- timeout；
- API credential、Base64 audio 与第三方响应正文不进入异常消息。

本次 Provider replacement 新增/修改：

- 新增 `backend/src/main/java/com/videoagent/asr/DashScopeAsrProvider.java`
- 新增 `backend/src/test/java/com/videoagent/asr/DashScopeAsrProviderTest.java`
- 修改 `AsrProviderConfiguration`、`AsrProviderProperties`
- 修改 `application.yml`、`.env.example`
- 修改 `RealAiInfrastructureSmokeTest`
- 修改 `AsrProviderConfigurationTest`
- 增加 `ASR_INPUT_TOO_LARGE`

没有进入 Milestone 7。
