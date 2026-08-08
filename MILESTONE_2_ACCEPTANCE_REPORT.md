# Milestone 2 验收报告

- 验收日期：2026-08-08（Asia/Shanghai）
- 规范范围：`VideoAgent_Codex_Spec.md` Milestone 2 — 视频上传
- 链路范围：Frontend → Spring Boot → MinIO + MySQL
- 最终结论：**PASS**
- Milestone 3：**未开始**

## 1. Git 基线

Milestone 2 开始前，当前目录不是 Git 仓库。已完成初始化、秘密文件复核及 Milestone 1 基线提交：

```text
b2e0fe4 feat: complete milestone 1 infrastructure
```

`.env` 验证结果：

- `git check-ignore -v .env` 命中 `.gitignore:2:.env`。
- `git ls-files --error-unmatch .env` 未找到文件，确认 `.env` 未被跟踪。
- 初始 commit 的 staged files 中不存在 `.env`、构建产物或本地依赖目录。

Milestone 1 已验收的 Docker Compose 架构没有在 M2 中修改。

## 2. 实施结果

### 2.1 数据库

Flyway migration：

```text
backend/src/main/resources/db/migration/V1__create_video_table.sql
```

`video` 表字段：

| 字段 | 类型/约束 | M2 用途 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED PK AUTO_INCREMENT | 视频 ID |
| `user_id` | BIGINT UNSIGNED NULL | V1 预留用户归属 |
| `title` | VARCHAR(255) NOT NULL | 展示标题 |
| `original_filename` | VARCHAR(255) NOT NULL | 原始文件名 |
| `object_key` | VARCHAR(512) NOT NULL UNIQUE | MinIO 对象键 |
| `file_size` | BIGINT UNSIGNED NOT NULL | 文件字节数 |
| `duration_seconds` | INT UNSIGNED NULL | 当前阶段不调用 FFmpeg，保持空值 |
| `mime_type` | VARCHAR(100) NOT NULL | `video/mp4` / `application/mp4` |
| `file_hash` | CHAR(64) NULL | 上传流的 SHA-256 |
| `status` | VARCHAR(32) NOT NULL | M2 只写入 `UPLOADED` |
| `created_at` | DATETIME(3) NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) NOT NULL | 更新时间 |

索引：主键、唯一 object key、`created_at`、`file_hash`。

实际数据库验证：

```text
Flyway version: 1
description: create video table
success: 1
video columns: 12
```

### 2.2 MinIO object key

设计：

```text
videos/{yyyy}/{MM}/{dd}/{uuid}.mp4
```

理由：

- UUID 避免并发上传重名。
- 日期目录便于运维定位与生命周期管理。
- object key 不包含用户文件名，避免路径注入、乱码和特殊字符问题。
- 原始文件名只保存在 MySQL 元数据中。

MinIO bucket 在第一次上传时按配置检查并创建。上传流只读取一次，同时计算 SHA-256。

### 2.3 上传一致性边界

实际顺序：

```text
校验文件 → 上传 MinIO → INSERT video → 返回 videoId
```

如果 MinIO 上传失败，不写数据库。如果 MinIO 成功而数据库插入失败，Service 会尝试删除刚写入的对象作为补偿，并保留错误日志。M2 不引入分布式事务或 Transactional Outbox。

### 2.4 REST API

已实现：

```text
POST /api/videos
GET  /api/videos
GET  /api/videos/{videoId}
```

上传使用普通 multipart：

- 必填字段：`file`
- 可选字段：`title`
- 只接受 `.mp4`、允许的 MP4 MIME type 和 MP4 `ftyp` 文件头
- 默认最大文件大小：500 MB
- `spring.servlet.multipart` 同时限制单文件与请求大小

Entity 不直接暴露给前端；列表与详情均返回 `VideoResponse` DTO。

### 2.5 异常处理

已提供统一 JSON 错误响应：

```json
{
  "timestamp": "...",
  "status": 415,
  "code": "VIDEO_FORMAT_NOT_SUPPORTED",
  "message": "仅支持 MP4 视频",
  "path": "/api/videos"
}
```

覆盖的必要错误包括：非法请求、视频不存在、不支持的格式、文件超限、上传失败、对象存储错误和内部错误。

### 2.6 前端

已实现：

- 视频列表页 `/`
- multipart 上传页 `/upload`
- 最小视频元数据详情页 `/videos/{id}`
- 前端 MP4 扩展名、MIME type、500 MB 大小和标题校验
- 上传进度展示
- 后端统一错误消息展示
- 上传成功后跳转详情，列表刷新后显示新记录

## 3. 自动化测试与构建

### 3.1 后端清洁构建

```powershell
mvn clean verify
```

结果：

```text
BUILD SUCCESS
main sources compiled: 20
test sources compiled: 7
tests run: 12
failures: 0
errors: 0
skipped: 2
```

两个默认跳过项是需要本机 Docker 的健康基础设施测试和 M2 上传基础设施测试。

普通测试覆盖：

- 健康接口单元/HTTP 集成测试
- MP4 格式与大小校验
- MinIO 上传后数据库写入编排
- 数据库失败时 MinIO 补偿删除
- multipart Controller
- 视频列表、详情和结构化 404

### 3.2 真实基础设施集成测试

```powershell
$env:VIDEOAGENT_M2_INFRA_TEST='true'
mvn '-Dtest=VideoUploadInfrastructureIntegrationTest' test
```

结果：

```text
BUILD SUCCESS
tests run: 1
failures: 0
errors: 0
skipped: 0
```

该测试实际完成：启动 Spring Boot、应用 Flyway、multipart 上传、MinIO SDK `statObject`、MySQL Repository 查询、列表 API、详情 API，并在结束后清理测试对象和记录。

### 3.3 前端生产构建

```powershell
npm run build
```

结果：

```text
vue-tsc: PASS
Vite build: PASS
modules transformed: 1675
```

## 4. 真实端到端验收

### 4.1 测试文件

使用忽略目录中的确定性 MP4 容器测试文件：

```text
tmp/milestone2-acceptance.mp4
size: 40 bytes
boxes: ftyp + mdat + moov
```

该文件用于验证上传与持久化，不用于播放能力测试；播放不属于 M2 范围。

### 4.2 浏览器上传

实际启动构建出的后端 JAR和 Vite 前端，在浏览器执行：

1. 打开 `http://127.0.0.1:5173/`，初始列表为空。
2. 打开 `/upload`。
3. 选择 `milestone2-acceptance.mp4`。
4. 页面自动生成标题 `milestone2-acceptance`，上传按钮启用。
5. 点击上传。
6. 页面显示“视频上传成功”并跳转 `/videos/2`。
7. 详情显示文件名、40 B、`video/mp4`、状态“已上传”。
8. 返回视频库，列表显示同一视频记录。

浏览器控制台错误数：`0`。

### 4.3 API 证据

实际 `GET /api/videos` 和 `GET /api/videos/2` 均返回：

```json
{
  "id": 2,
  "title": "milestone2-acceptance",
  "originalFilename": "milestone2-acceptance.mp4",
  "fileSize": 40,
  "durationSeconds": null,
  "mimeType": "video/mp4",
  "status": "UPLOADED",
  "createdAt": "2026-08-08T20:40:42.905",
  "updatedAt": "2026-08-08T20:40:42.905"
}
```

### 4.4 MySQL 证据

实际查询 `video.id=2`：

```text
id:                2
title:             milestone2-acceptance
original_filename: milestone2-acceptance.mp4
object_key:        videos/2026/08/08/55c262a3-2c55-453e-8433-2e4d54942b2a.mp4
file_size:         40
mime_type:         video/mp4
file_hash length:  64
status:            UPLOADED
created_at:        2026-08-08 20:40:42.905
```

### 4.5 MinIO 证据

在 MinIO 数据目录中按数据库 object key 找到对象元数据：

```text
/data/videoagent/videos/2026/08/08/55c262a3-2c55-453e-8433-2e4d54942b2a.mp4/xl.meta
```

同时，真实基础设施集成测试已经通过 MinIO Java SDK `statObject` 验证上传对象大小。

### 4.6 负向验证

扩展名错误的 multipart 上传：

```text
HTTP 415
code: VIDEO_FORMAT_NOT_SUPPORTED
```

不存在的视频详情：

```text
GET /api/videos/999999
HTTP 404
code: VIDEO_NOT_FOUND
```

## 5. 实际执行过的主要命令

```powershell
# Git 基线
git init -b main
git check-ignore -v .env
git add .
git diff --cached --name-only
git ls-files --error-unmatch .env
git commit -m "feat: complete milestone 1 infrastructure"
git log -1 --oneline

# 基础设施
docker compose up -d
docker compose ps --all

# 后端编译与测试
mvn clean -DskipTests compile
mvn test
$env:VIDEOAGENT_M2_INFRA_TEST='true'; mvn '-Dtest=VideoUploadInfrastructureIntegrationTest' test
mvn clean verify

# 前端
npm run build
npm run dev -- --host 127.0.0.1

# 真实应用
& 'D:\java\jdk23\bin\java.exe' -jar target\videoagent-backend-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/api/videos
Invoke-RestMethod http://127.0.0.1:8080/api/videos/2

# MySQL / MinIO 证据
docker exec --env "MYSQL_PWD=<隐藏>" videoagent-mysql mysql ...
docker exec videoagent-minio sh -c 'ls -la /data/videoagent/videos/2026/08/08/55c262a3-2c55-453e-8433-2e4d54942b2a.mp4'

# 错误响应与范围检查
curl.exe --form "file=@tmp/milestone2-acceptance.mp4;filename=invalid.avi;type=video/mp4" http://127.0.0.1:8080/api/videos
curl.exe http://127.0.0.1:8080/api/videos/999999
rg -n -i --glob 'backend/src/**' --glob 'frontend/src/**' '<M3+ 禁止能力关键词>'
git diff --check
```

此外使用本地浏览器实际完成文件选择、上传提交、详情页和列表页验证。

## 6. 新增/修改文件

```text
.env.example
IMPLEMENTATION_PLAN.md
README.md

backend/src/main/java/com/videoagent/VideoAgentApplication.java
backend/src/main/java/com/videoagent/common/exception/ApiErrorResponse.java
backend/src/main/java/com/videoagent/common/exception/ErrorCode.java
backend/src/main/java/com/videoagent/common/exception/GlobalExceptionHandler.java
backend/src/main/java/com/videoagent/common/exception/VideoAgentException.java
backend/src/main/java/com/videoagent/storage/MinioStorageService.java
backend/src/main/java/com/videoagent/storage/ObjectStorageService.java
backend/src/main/java/com/videoagent/storage/StorageConfiguration.java
backend/src/main/java/com/videoagent/storage/StorageProperties.java
backend/src/main/java/com/videoagent/video/controller/VideoController.java
backend/src/main/java/com/videoagent/video/dto/VideoResponse.java
backend/src/main/java/com/videoagent/video/dto/VideoUploadResponse.java
backend/src/main/java/com/videoagent/video/entity/VideoEntity.java
backend/src/main/java/com/videoagent/video/repository/VideoRepository.java
backend/src/main/java/com/videoagent/video/service/ValidatedVideoFile.java
backend/src/main/java/com/videoagent/video/service/VideoFileValidator.java
backend/src/main/java/com/videoagent/video/service/VideoService.java
backend/src/main/java/com/videoagent/video/service/VideoUploadProperties.java
backend/src/main/resources/application.yml
backend/src/main/resources/db/migration/V1__create_video_table.sql
backend/src/test/java/com/videoagent/common/health/HealthEndpointIntegrationTest.java
backend/src/test/java/com/videoagent/video/VideoUploadInfrastructureIntegrationTest.java
backend/src/test/java/com/videoagent/video/controller/VideoControllerTest.java
backend/src/test/java/com/videoagent/video/service/VideoFileValidatorTest.java
backend/src/test/java/com/videoagent/video/service/VideoServiceTest.java

frontend/src/App.vue
frontend/src/router/index.ts
frontend/src/services/api.ts
frontend/src/services/video.ts
frontend/src/styles/main.css
frontend/src/types/video.ts
frontend/src/views/UploadView.vue
frontend/src/views/VideoDetailView.vue
frontend/src/views/VideoListView.vue
frontend/src/views/HomeView.vue                   # 删除：已被 M2 页面替代

MILESTONE_2_ACCEPTANCE_REPORT.md
```

`backend/target/`、`frontend/dist/`、`frontend/node_modules/` 与 `tmp/` 均被 Git 忽略，不属于源码变更。

## 7. 当前目录结构

```text
Video agent/
├─ .env                                  # 本地秘密，已忽略
├─ .env.example
├─ .gitignore
├─ .git/
├─ docker-compose.yml
├─ IMPLEMENTATION_PLAN.md
├─ MILESTONE_1_ACCEPTANCE_REPORT.md
├─ MILESTONE_2_ACCEPTANCE_REPORT.md
├─ README.md
├─ VideoAgent_Codex_Spec.md
├─ backend/
│  ├─ pom.xml
│  ├─ src/main/
│  │  ├─ java/com/videoagent/
│  │  │  ├─ common/health/
│  │  │  ├─ common/exception/
│  │  │  ├─ storage/
│  │  │  ├─ video/controller/
│  │  │  ├─ video/dto/
│  │  │  ├─ video/entity/
│  │  │  ├─ video/repository/
│  │  │  └─ video/service/
│  │  └─ resources/
│  │     ├─ application.yml
│  │     └─ db/migration/V1__create_video_table.sql
│  └─ src/test/java/com/videoagent/
│     ├─ common/health/
│     └─ video/
├─ frontend/
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ vite.config.ts
│  └─ src/
│     ├─ App.vue
│     ├─ router/
│     ├─ services/
│     ├─ styles/
│     ├─ types/
│     └─ views/
├─ infra/rocketmq/broker.conf
└─ tmp/                                  # 忽略的验收文件
```

## 8. 禁止范围检查

源码关键词扫描未发现以下 M3+ 实现：

- Redis 业务读写
- RocketMQ Producer / Consumer
- 视频分析任务或状态机
- FFmpeg
- ASR / transcript
- LLM / summary
- SSE / WebSocket
- RAG / embedding
- 分片上传、断点续传、秒传

现有 Redis、RocketMQ 依赖和 Docker 容器属于已通过验收的 Milestone 1 基础设施，本阶段没有新增任何相关业务代码。

## 9. 已知问题与边界

1. 本机 3306 已被其他进程占用，项目本地 MySQL 使用 3307；配置仍由 `.env` 控制。
2. Flyway 11.7.2 对 MySQL 8.4 给出“高于已验证 8.1”的升级建议，但 migration、查询和测试均成功。
3. Element Plus 当前完整引入，生产主 chunk 约 1.04 MB，Vite 给出非阻断大小警告。
4. `duration_seconds` 保持 `null`，因为时长提取属于后续 FFmpeg 里程碑。
5. MinIO 与 MySQL 不具备原子事务；当前采用数据库失败后的 best-effort 对象删除补偿。
6. 本阶段没有对象下载/播放接口、删除接口或分析入口，因为它们不在用户限定的 M2 目标内。
7. 系统默认 `java` 仍指向旧版本；构建和验收显式使用 JDK 23，并按 Java 21 release 编译。
8. 验收记录 `video.id=2` 及对应 MinIO 对象被有意保留，便于复核；测试 fixture 位于已忽略的 `tmp/`。
9. 只有 Milestone 1 基线已提交。M2 变更保持在工作区，用户未要求创建 M2 commit。

## 10. 最终判定

以下验收链路均已实际完成：

```text
浏览器上传测试 MP4
→ MinIO 找到 object key
→ MySQL 找到 video 记录
→ GET /api/videos 返回该记录
→ GET /api/videos/2 返回正确详情
→ 前端详情和列表均展示该视频
```

**Milestone 2：PASS。到此停止，未进入 Milestone 3。**
