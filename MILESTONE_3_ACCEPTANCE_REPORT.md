# Milestone 3 验收报告

验收日期：2026-08-08（Asia/Shanghai）  
验收结论：**PASS**  
里程碑：**Milestone 3 — 异步分析任务框架**

## 1. 范围结论

Milestone 3 已完成并通过代码、构建、真实基础设施和浏览器端到端验收。已建立以下闭环：

```text
视频详情页发起分析
  -> Spring Boot 持久化 PENDING analysis_task
  -> RocketMQ VIDEO_ANALYZE_TOPIC 投递 taskId/videoId
  -> HTTP 立即返回 taskId
  -> Consumer 后台模拟 20/40/70/90/100 阶段
  -> Redis 保存实时 JSON 进度和 24 小时 TTL
  -> MySQL 保存持久状态并最终 SUCCESS
  -> 前端普通轮询并显示排队、处理中、完成或失败
```

未实现 FFmpeg、ASR、Whisper、LLM、LangChain4j、transcript、summary、chapter、SSE、WebSocket、RAG、Embedding、分片上传、断点续传、秒传、Transactional Outbox、复杂分布式锁或多 Topic 流水线。未进入 Milestone 4。

## 2. Git 与秘密文件检查

- 仓库分支：`main`。
- Milestone 2 已作为独立检查点提交：
  - `25b5a7b feat: complete milestone 2 video upload`
- 上一个检查点：
  - `b2e0fe4 feat: complete milestone 1 infrastructure`
- `.env` 命中 `.gitignore` 第 2 行规则，`git ls-files --error-unmatch .env` 确认未被跟踪。
- `.env.example` 只包含占位值或空 API Key，没有真实密钥。
- 对已跟踪文件执行高置信密钥模式扫描，未发现 `sk-*`、AWS Access Key 或私钥头。
- Milestone 3 当前改动未提交，保留为独立工作区变更，等待后续检查点指令。

本机存在原生 `redis-server.exe` 占用 `127.0.0.1:6379`，会使 Spring Boot 绕过 Docker Redis。验收中将本地 `.env` 和 `.env.example` 的 `REDIS_PORT` 调整为 `6380`，并重建 Redis 容器映射。`.env` 仍是忽略文件；`docker-compose.yml`、服务拓扑和 Milestone 1 架构均未修改。

## 3. analysis_task 表设计

Flyway migration：`V2__create_analysis_task_table.sql`。

| 字段 | 设计 |
| --- | --- |
| `id` | `BIGINT UNSIGNED` 自增主键 |
| `video_id` | 视频外键，`ON DELETE RESTRICT` |
| `analysis_type` | 当前为 `FRAMEWORK`，区分分析语义 |
| `model_version` | 当前为 `m3-simulation-v1`，允许未来版本形成新业务键 |
| `status` | `PENDING / PROCESSING / SUCCESS / FAILED` |
| `stage` | `QUEUED / PREPARING / ANALYZING / PROCESSING / SAVING / DONE / FAILED` |
| `progress` | `0..100`，数据库 CHECK 约束 |
| `retry_count` | 当前初始化为 0，为后续有限重试保留持久字段 |
| `error_code/error_message` | 失败诊断，消息上限 1000 字符 |
| `started_at/finished_at` | 处理生命周期时间 |
| `created_at/updated_at` | 业务创建和最近持久更新 |

约束与索引理由：

- `uk_analysis_task_business(video_id, analysis_type, model_version)`：数据库级阻止并发重复创建；同一视频可通过不同分析类型或模型版本产生不同任务。
- `idx_analysis_task_video_created(video_id, created_at)`：支持按视频查看任务历史。
- `idx_analysis_task_status_updated(status, updated_at)`：支持按状态和更新时间进行运维排查或后续恢复扫描；本阶段未实现调度器。
- 外键阻止仍有任务记录的视频被误删；MySQL 是任务最终事实源。

## 4. Producer / Consumer 边界

Producer：

- 只向 `VIDEO_ANALYZE_TOPIC` 发送 `{ "taskId": ..., "videoId": ... }`。
- 不发送视频二进制、对象内容或大 DTO。
- PENDING 行通过独立事务先提交，再同步等待 Broker 接收确认；HTTP 不等待 Consumer 执行。
- Broker 发送失败时将任务持久化为 FAILED，并返回 `ANALYSIS_DISPATCH_FAILED`（503）。

Consumer：

- 只根据 `taskId` 回查 MySQL，不信任消息携带业务状态。
- SUCCESS 任务收到重复消息时直接跳过。
- PENDING 到 PROCESSING 使用带 `WHERE status = 'PENDING'` 的原子更新抢占，两个 Consumer 不能同时执行。
- 模拟阶段为 20 PREPARING、40 ANALYZING、70 PROCESSING、90 SAVING、100 DONE/SUCCESS。
- 首次自动创建 Topic 后，Consumer 将 NameServer 路由刷新周期设为 1 秒，避免首条消息等待客户端默认刷新周期。

## 5. Redis 与 MySQL 职责

Redis：

- Key：`video:analysis:progress:{taskId}`。
- Value：`status`、`stage`、`progress`、`message` JSON。
- TTL：24 小时；最终验收任务的实测 TTL 为 `86387` 秒。
- 读写失败会记录告警并降级，不阻断 MySQL 状态推进。

MySQL：

- 保存任务业务键、状态、阶段、进度、错误和生命周期时间，是最终事实源。
- 处理中任务查询优先使用 Redis 快照；Redis 缺失/损坏时回退 MySQL。
- MySQL 已为 SUCCESS/FAILED 时，即使 Redis 存在陈旧处理中快照，也以 MySQL 终态为准。

## 6. API 与异常处理

| 接口 | 结果 |
| --- | --- |
| `POST /api/videos/{videoId}/analysis` | 202，返回 `taskId/videoId/status=PENDING` |
| `GET /api/analysis/{taskId}` | 返回实时或持久回退状态 |
| 不存在 video 发起分析 | 404 `VIDEO_NOT_FOUND` |
| 重复业务任务 | 409 `ANALYSIS_ALREADY_RUNNING`，不插入第二条记录 |
| 不存在 analysis task | 404 `ANALYSIS_NOT_FOUND` |
| RocketMQ 投递失败 | 503 `ANALYSIS_DISPATCH_FAILED`，MySQL 任务标为 FAILED |

Controller 只负责路径映射和 HTTP 状态，业务、事务、投递、消费和查询回退分别位于 Service/Producer/Consumer/Progress Store。

## 7. 实际执行的关键命令

Git 与秘密检查：

```powershell
git status --short --branch
git check-ignore -v .env
git ls-files --error-unmatch .env
git commit -m "feat: complete milestone 2 video upload"
git log -2 --oneline
git diff --check
git grep -n -I -E '(sk-...|AKIA...|BEGIN ... PRIVATE KEY)'
```

基础设施与端口冲突修复：

```powershell
docker compose ps
docker compose up -d --force-recreate redis
docker compose port redis 6379
Get-NetTCPConnection -LocalPort 6379 -State Listen
Get-NetTCPConnection -OwningProcess <backend-pid>
```

后端编译、测试和真实联调：

```powershell
Set-Location backend
mvn -DskipTests compile
mvn test
$env:VIDEOAGENT_M3_INFRA_TEST = 'true'
mvn '-Dtest=AnalysisFrameworkInfrastructureIntegrationTest' test
mvn spring-boot:run
```

前端：

```powershell
Set-Location frontend
npm run build
npm run dev -- --host 127.0.0.1
```

真实数据核对：

```powershell
curl.exe -F "file=@tmp/milestone2-acceptance.mp4;type=video/mp4" `
  -F "title=M3 Docker Redis Acceptance" http://127.0.0.1:8080/api/videos

Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/videos/<videoId>/analysis
Invoke-RestMethod http://127.0.0.1:8080/api/analysis/<taskId>
docker compose exec -T redis redis-cli GET video:analysis:progress:<taskId>
docker compose exec -T redis redis-cli TTL video:analysis:progress:<taskId>
docker compose exec -T mysql mysql <credentials-from-untracked-env> --execute=<read-only-select>
```

另外使用应用内浏览器实际打开 `http://127.0.0.1:5173/videos/13`，点击“开始 AI 分析”，连续读取页面可访问状态和进度条属性。

## 8. 验证结果

### 8.1 构建和测试

| 项目 | 结果 |
| --- | --- |
| 后端编译 | PASS，39 个 main source 编译成功 |
| 后端完整测试 | PASS，31 tests，0 failures，0 errors，4 skipped |
| M3 真实基础设施测试 | PASS，2 tests，0 failures/errors/skips |
| 前端 TypeScript + Vite 生产构建 | PASS，1676 modules transformed |
| `git diff --check` | PASS，无空白错误 |

普通 `mvn test` 中跳过的是显式环境开关控制的真实基础设施测试；M3 测试已单独打开环境变量并实际执行 2/2。

### 8.2 真实异步链路

最终保留的可复核记录：

- Video：`id=13`，标题 `M3 Docker Redis Acceptance`。
- Analysis task：`id=8`。
- MySQL：`FRAMEWORK / m3-simulation-v1 / SUCCESS / DONE / 100 / retry_count=0`。
- MySQL 时间：创建 `21:30:02.417`，开始 `21:30:03.526`，结束 `21:30:06.387`。
- Docker Redis：`{"status":"SUCCESS","stage":"DONE","progress":100,"message":"分析完成"}`。
- Docker Redis TTL：`86387` 秒。
- 后端 TCP：实际建立到 `127.0.0.1:6380` 的连接。
- GET API：`taskId=8, videoId=13, status=SUCCESS, stage=DONE, progress=100`。

真实基础设施测试还直接断言：

- POST 在 3 秒内返回 PENDING taskId。
- Consumer 抢占前，MySQL 可读到 PENDING/0。
- Redis 可采样到 PROCESSING 且非 100 的中间快照。
- MySQL 最终 SUCCESS/DONE/100，startedAt/finishedAt 非空。
- 重复 POST 返回 409，未创建第二条业务任务。
- 手工再次处理 SUCCESS 消息后 `updated_at` 不变，未重新执行。
- 删除 Redis key 后，GET 仍从 MySQL 返回 SUCCESS/100。
- 不存在视频返回 404 `VIDEO_NOT_FOUND`。

### 8.3 浏览器前端

任务 #8 的页面连续采样：

| 相对时间 | 页面状态/消息 |
| --- | --- |
| 350–1050 ms | 排队中 / 任务已进入队列 |
| 1400 ms | 处理中 / 正在准备分析 |
| 1750–2450 ms | 处理中 / 正在模拟分析 |
| 2800 ms | 处理中 / 正在处理分析结果 |
| 3150–3500 ms | 处理中 / 正在保存结果 |
| 3850 ms | 已完成 / 分析完成 |

进度条存在且最终 `aria-valuenow=100`。按钮在处理中不可重复点击，完成后显示“分析已完成”。

## 9. 修改文件

配置与文档：

- `.env.example`
- `IMPLEMENTATION_PLAN.md`
- `README.md`
- `backend/src/main/resources/application.yml`
- 本地忽略文件 `.env`：仅将 Docker Redis 主机端口调整为 6380，不会进入 Git

数据库与公共异常：

- `backend/src/main/resources/db/migration/V2__create_analysis_task_table.sql`
- `backend/src/main/java/com/videoagent/common/exception/ErrorCode.java`

后端 Analysis 模块：

- `analysis/controller/AnalysisCommandController.java`
- `analysis/controller/AnalysisQueryController.java`
- `analysis/service/AnalysisCommandService.java`
- `analysis/service/AnalysisTaskPersistenceService.java`
- `analysis/service/AnalysisQueryService.java`
- `analysis/service/AnalysisProperties.java`
- `analysis/producer/AnalysisTaskProducer.java`
- `analysis/consumer/AnalysisTaskConsumer.java`
- `analysis/consumer/AnalysisTaskProcessor.java`
- `analysis/progress/AnalysisProgressStore.java`
- `analysis/progress/RedisAnalysisProgressStore.java`
- `analysis/repository/AnalysisTaskRepository.java`
- `analysis/entity/AnalysisTaskEntity.java`
- `analysis/entity/AnalysisStatus.java`
- `analysis/entity/AnalysisStage.java`
- `analysis/dto/AnalysisMessage.java`
- `analysis/dto/AnalysisProgressSnapshot.java`
- `analysis/dto/AnalysisTaskResponse.java`
- `analysis/dto/StartAnalysisResponse.java`

后端测试：

- `analysis/AnalysisFrameworkInfrastructureIntegrationTest.java`
- `analysis/consumer/AnalysisTaskConsumerTest.java`
- `analysis/consumer/AnalysisTaskProcessorTest.java`
- `analysis/controller/AnalysisControllerTest.java`
- `analysis/progress/RedisAnalysisProgressStoreTest.java`
- `analysis/service/AnalysisCommandServiceTest.java`
- `analysis/service/AnalysisQueryServiceTest.java`
- `analysis/service/AnalysisTaskPersistenceServiceTest.java`
- `common/health/HealthEndpointIntegrationTest.java`（为无基础设施健康测试补 M3 bean mock）

前端：

- `frontend/src/services/analysis.ts`
- `frontend/src/types/analysis.ts`
- `frontend/src/views/VideoDetailView.vue`
- `frontend/src/styles/main.css`
- `frontend/src/App.vue`

## 10. 当前目录结构

```text
Video agent/
├── backend/
│   ├── src/main/java/com/videoagent/
│   │   ├── analysis/
│   │   │   ├── consumer/
│   │   │   ├── controller/
│   │   │   ├── dto/
│   │   │   ├── entity/
│   │   │   ├── producer/
│   │   │   ├── progress/
│   │   │   ├── repository/
│   │   │   └── service/
│   │   ├── common/
│   │   ├── storage/
│   │   └── video/
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_video_table.sql
│   │   └── V2__create_analysis_task_table.sql
│   └── src/test/java/com/videoagent/
│       ├── analysis/
│       ├── common/
│       └── video/
├── frontend/
│   └── src/
│       ├── services/{api,video,analysis}.ts
│       ├── types/{video,analysis}.ts
│       ├── views/{VideoListView,UploadView,VideoDetailView}.vue
│       ├── router/
│       └── styles/
├── infra/rocketmq/
├── docker-compose.yml
├── .env.example
├── IMPLEMENTATION_PLAN.md
├── MILESTONE_1_ACCEPTANCE_REPORT.md
├── MILESTONE_2_ACCEPTANCE_REPORT.md
├── MILESTONE_3_ACCEPTANCE_REPORT.md
├── README.md
└── VideoAgent_Codex_Spec.md
```

模块保持单体内清晰分层，没有新增微服务、通用框架层、复杂锁、额外 Topic 或无意义抽象。

## 11. 已知问题与边界

- 本机原生 Redis 仍占用 6379；项目 Docker Redis 已改用 6380，README 和 `.env.example` 已同步。
- DB 事务与 MQ 不是原子提交；发送失败会标 FAILED，但极端宕机窗口仍可能产生 PENDING 未投递任务。按本阶段禁令未实现 Transactional Outbox。
- 业务唯一约束使同一视频/类型/模型版本只保留一个任务；本阶段没有失败任务手工重试或新版本重跑 UI。
- 页面刷新后不会自动恢复此前 taskId；当前验收流程是点击后在同一详情页轮询。
- 浏览器/探针手工验收产生的记录保留用于复核；自动化基础设施测试会清理自己的临时记录。
- Maven 有既存的 Commons Logging、Mockito 动态 Agent、MySQL 8.4/Flyway 支持范围告警；不影响编译和测试。
- Vite 构建提示主 bundle 大于 500 kB；当前页面规模可运行，按“不做无关优化”未引入额外拆包设计。
- 当前任务处理完全是短时模拟，不产生媒体、字幕、摘要、章节或 AI 结果，这是 Milestone 3 的明确边界。

## 12. 最终结论

**Milestone 3：PASS。**

当前代码满足进入 Milestone 4 的技术前置条件，但本次未开始 Milestone 4。后续必须等待明确指令后再创建 Milestone 3 Git 检查点或进入下一阶段。
