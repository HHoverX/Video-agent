# Milestone 1 验收报告

- 验收日期：2026-08-08（Asia/Shanghai）
- 验收范围：`VideoAgent_Codex_Spec.md` 第 21 节 Milestone 1 及用户补充的完整验收项
- 最终结论：**PASS**
- 是否可以进入 Milestone 2：**可以，但本次未开始 Milestone 2，也未增加其功能**

## 1. 规范验收结论

| 验收项 | 结果 | 实际结果 |
| --- | --- | --- |
| 项目目录与职责划分 | PASS | 根目录负责环境与编排，`backend/`、`frontend/`、`infra/` 职责清晰 |
| `docker-compose.yml` 配置解析 | PASS | `docker compose config --quiet` 成功 |
| MySQL | PASS | 容器 healthy，`mysqladmin ping` 返回 `mysqld is alive` |
| Redis | PASS | 容器 healthy，`redis-cli ping` 返回 `PONG` |
| MinIO | PASS | 容器 healthy，`/minio/health/live` 返回 HTTP 200 |
| RocketMQ NameServer | PASS | 容器 healthy，9876 端口健康检查通过 |
| RocketMQ Broker | PASS | 容器 healthy；日志确认 broker 启动成功并注册到 NameServer |
| 后端清洁编译 | PASS | `mvn clean verify` 为 `BUILD SUCCESS`，生成可执行 JAR |
| 后端测试 | PASS | 普通验证 2 个测试通过、基础设施测试默认跳过；随后显式执行基础设施测试并通过，0 failure / 0 error |
| 真实后端启动 | PASS | 使用构建出的 JAR 启动，成功连接当前基础设施 |
| `GET /api/health` | PASS | HTTP 200，返回 `status=UP`、`application=videoagent-api` 和时间戳 |
| Actuator 健康检查 | PASS | `GET /actuator/health` 返回 HTTP 200、`status=UP` |
| 前端清洁安装 | PASS | `npm ci` 成功，安装 115 个包 |
| 前端生产构建 | PASS | `npm run build` 成功，生成 `frontend/dist/` |
| `.env.example` 与密钥 | PASS（当前文件级） | 仅包含 `change-me-*` 占位值和空 API Key；真实 `.env` 已被 `.gitignore` 排除 |
| Milestone 范围 | PASS | 未发现上传、转码、ASR、摘要、SSE、RAG、对话等 Milestone 2+ 实现 |
| 编译/配置/过度设计检查 | PASS | 未发现阻断问题或明显无意义的过度设计 |

## 2. Docker Compose 与基础设施

最终 `docker compose ps --all` 显示以下五个容器均为 `Up (... healthy)`：

```text
videoagent-mysql
videoagent-redis
videoagent-minio
videoagent-rocketmq-namesrv
videoagent-rocketmq-broker
```

所有容器最终复核时重启次数均为 0。Compose 使用独立 bridge 网络、具名数据卷、固定镜像版本和健康检查；Broker 等待 NameServer healthy 后启动。RocketMQ Broker 在 Windows Docker Desktop 的具名卷上先修正 store 目录所有权，再降权为 `rocketmq` 用户运行 Broker，已通过重复启动和健康验证。

本机已有进程占用 3306，验收没有终止或修改该进程；本项目本地 `.env` 将 MySQL 映射为 `3307:3306`。Compose 本身通过 `${MYSQL_PORT}` 保持可配置，`.env.example` 仍给出标准默认端口 3306。

## 3. 后端验证

`mvn clean verify` 清理旧生成物后重新编译 3 个主源码和 3 个测试源码，构建成功并生成：

```text
backend/target/videoagent-backend-0.0.1-SNAPSHOT.jar
```

测试执行情况：

```text
HealthControllerTest                         1 passed
HealthEndpointIntegrationTest                1 passed
InfrastructureBackedHealthIntegrationTest    默认构建跳过；显式启用后 1 passed
Failures                                     0
Errors                                       0
```

随后用构建出的 JAR 启动真实应用并发送 HTTP 请求，实际响应为：

```json
{"status":"UP","application":"videoagent-api","timestamp":"2026-08-08T10:28:37.666429200Z"}
```

Actuator 响应：

```json
{"status":"UP"}
```

验收用后端进程已停止，并复核 8080 不再响应。基础设施容器保持运行。

## 4. 前端验证

在 `frontend/` 中执行 `npm ci` 与 `npm run build` 均成功。Vite 完成生产构建，共转换 1670 个模块，并生成 `dist/index.html`、CSS 和 JavaScript 资源。

## 5. 密钥与配置检查

验收时发现 `backend/src/main/resources/application.yml` 原本为数据库和 MinIO 凭证提供了硬编码的本地回退值。该配置不符合“敏感配置只通过环境变量注入”的要求，已经移除；现在未设置环境变量时凭证为空，不再隐式使用固定密码。

检查结果：

- `.env.example` 只有明确的 `change-me-*` 占位值及空的 `ASR_API_KEY`、`LLM_API_KEY`。
- `application.yml` 只引用环境变量，不包含固定密码或 API Key。
- `docker-compose.yml` 只引用环境变量。
- `.gitignore` 明确忽略 `.env`、`*.local`、构建产物和本地 Maven 仓库。
- 当前目录不是 Git 仓库，因此无法审计历史提交；结论仅覆盖当前工作区文件，不能对不存在的 Git 历史作保证。

## 6. 范围与设计检查

源码扫描未发现以下 Milestone 2 及以后功能：视频上传、MinIO 对象写入业务、视频记录、任务状态机、RocketMQ 业务生产/消费、FFmpeg、ASR、摘要、SSE、RAG、聊天或 WebSocket。

当前业务源码仅包含应用启动、健康响应、前端健康状态页和对应测试。配置中保留的第三方环境变量占位符没有对应业务实现，不构成提前实现功能。

## 7. 实际执行过的命令

以下命令均在本项目工作区执行；涉及本地秘密的参数未在报告中展开：

```powershell
# 目录与范围
rg --files --hidden -g '!backend/target/**' -g '!backend/.m2-repository/**' -g '!frontend/node_modules/**' -g '!frontend/dist/**'
rg -n -i --glob 'backend/src/**' --glob 'frontend/src/**' 'PostMapping|/upload|multipart|ffmpeg|whisper|transcri|summar|vector|embedding|rag|chat|sse|websocket|rocketmq.*producer|RocketMQTemplate'

# Compose 与基础设施
docker compose config --quiet
docker compose up -d
docker compose ps --all
docker inspect <五个容器> --format '<状态、健康状态、重启次数>'
docker exec videoagent-mysql mysqladmin ping -h 127.0.0.1 -uroot -p<已隐藏>
docker exec videoagent-redis redis-cli ping
Invoke-WebRequest http://127.0.0.1:9000/minio/health/live
docker exec videoagent-rocketmq-broker grep 'boot success' /home/rocketmq/logs/rocketmqlogs/broker.log

# 后端
cd backend
mvn clean verify
$env:VIDEOAGENT_INFRA_TEST='true'; mvn '-Dtest=InfrastructureBackedHealthIntegrationTest' test
& 'D:\java\jdk23\bin\java.exe' -jar target\videoagent-backend-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health

# 前端
cd frontend
npm ci
npm run build

# 密钥与停止状态复核
rg -n --hidden --glob '!.env' --glob '!backend/target/**' --glob '!backend/.m2-repository/**' --glob '!frontend/node_modules/**' --glob '!frontend/dist/**' '(?i)(password|secret|api[_-]?key|access[_-]?key)' .env.example .gitignore docker-compose.yml backend frontend infra README.md IMPLEMENTATION_PLAN.md
Invoke-WebRequest http://127.0.0.1:8080/api/health -TimeoutSec 3
```

最后一条请求在验收进程停止后按预期连接失败，用于确认后端已停止。

## 8. 本次验收修改的文件

```text
backend/src/main/resources/application.yml
MILESTONE_1_ACCEPTANCE_REPORT.md
```

- `application.yml`：移除数据库用户名/密码和 MinIO Access Key/Secret Key 的硬编码本地回退值。
- 本文件：记录验收命令、证据、结论和已知问题。

`backend/target/`、`frontend/node_modules/` 和 `frontend/dist/` 是验收命令生成或更新的忽略目录，不属于源代码修改。

## 9. 当前项目目录结构

```text
Video agent/
├─ .env                              # 本地配置，已忽略，内容未写入报告
├─ .env.example                      # 可提交的占位配置
├─ .gitignore
├─ docker-compose.yml
├─ IMPLEMENTATION_PLAN.md
├─ MILESTONE_1_ACCEPTANCE_REPORT.md
├─ README.md
├─ VideoAgent_Codex_Spec.md
├─ backend/
│  ├─ .mvn/
│  │  ├─ maven.config
│  │  └─ settings.xml
│  ├─ pom.xml
│  ├─ src/
│  │  ├─ main/
│  │  │  ├─ java/com/videoagent/
│  │  │  │  ├─ VideoAgentApplication.java
│  │  │  │  └─ common/health/
│  │  │  │     ├─ HealthController.java
│  │  │  │     └─ HealthResponse.java
│  │  │  └─ resources/application.yml
│  │  └─ test/java/com/videoagent/common/health/
│  │     ├─ HealthControllerTest.java
│  │     ├─ HealthEndpointIntegrationTest.java
│  │     └─ InfrastructureBackedHealthIntegrationTest.java
│  ├─ .m2-repository/                # 本地依赖缓存，已忽略
│  └─ target/                        # 构建产物，已忽略
├─ frontend/
│  ├─ index.html
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ vite.config.ts
│  ├─ tsconfig.json
│  ├─ tsconfig.app.json
│  ├─ tsconfig.node.json
│  ├─ src/
│  │  ├─ App.vue
│  │  ├─ env.d.ts
│  │  ├─ main.ts
│  │  ├─ router/index.ts
│  │  ├─ services/api.ts
│  │  ├─ styles/main.css
│  │  └─ views/HomeView.vue
│  ├─ node_modules/                  # 安装产物，已忽略
│  └─ dist/                          # 构建产物，已忽略
├─ infra/
│  └─ rocketmq/broker.conf
└─ tmp/                              # 空的运行时目录，已忽略
```

## 10. 已知问题与非阻断警告

1. 当前目录没有 `.git`，所以无法检查历史提交中是否曾出现秘密；当前工作区与忽略规则检查通过。
2. 本机 3306 已被其他受保护进程占用，本项目当前使用宿主机 3307；这是本机环境差异，不影响 Compose 或后端连接。
3. Flyway 11.7.2 提示 MySQL 8.4 高于其已验证的 8.1，但实际连接、schema history 检查和启动均成功。后续升级依赖时应消除此兼容性提示。
4. 前端主 JavaScript 包约 1.04 MB，Vite 给出 chunk size 警告，主要来自当前完整引入 Element Plus；不影响 Milestone 1 构建，优化不应在本次验收中扩展范围。
5. Maven 测试日志存在 Mockito 动态 agent、Commons Logging 和 RocketMQ BeanPostProcessor 警告，均未导致功能或测试失败。
6. 系统默认 `java` 命令指向旧版本，Maven/验收启动使用明确的 JDK 23 路径并以 Java 21 release 编译。进入后续开发前建议统一本机 `JAVA_HOME`，但这不阻断当前里程碑。

## 11. 最终判定

Milestone 1 满足规范中的项目骨架、五项基础设施启动和 `GET /api/health` 成功要求，也通过了补充的编译、测试、前端构建、密钥与范围审查。

**判定：PASS。可以在收到明确指令后进入 Milestone 2；本次验收到此停止。**
