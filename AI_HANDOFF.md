# VideoAgent AI Handoff

## 项目目标

这是一个面向 Java 后端 / AI 应用面试学习的 Video Agent 项目。

采用自顶向下学习：

1. 使用 AI 完成项目
2. 再反向解构学习
3. 最后进行面试训练

因此：

代码可读性、模块边界和设计理由
比过度优化更重要。

---

## 当前技术栈

Backend:
Spring Boot
MyBatis-Plus
MySQL
Redis
RocketMQ
MinIO

Frontend:
Vue 3
TypeScript

AI:
ASR Provider
LLM Provider
LangChain4j（后续）

Media:
FFmpeg

---

## 已完成

M1 Infrastructure
M2 Video Upload
M3 Async Analysis Framework

当前：
M4 FFmpeg + Mock ASR

---

## 核心架构原则

1. MySQL 是业务最终事实源。
2. Redis 只存高频实时状态，不作为最终任务状态。
3. RocketMQ 负责视频分析长任务异步化。
4. Consumer 必须考虑重复消费和幂等。
5. MinIO 保存视频对象，MySQL 保存 object key。
6. ASR 是外部能力，不深入模型算法。
7. FFmpeg 只是媒体工具，不作为项目核心卖点。
8. V1 不进行微服务化。
9. 不擅自加入新的中间件。
10. 不提前实现后续 Milestone。

---

## 禁止擅自重构

除非当前 Milestone 必须，否则不要：

- 更换技术栈
- 重构已验收模块
- 修改数据库历史 migration
- 删除现有测试
- 引入微服务
- 引入 Kafka
- 引入 Kubernetes
- 引入 Elasticsearch
- 引入多 Agent
- 改变 Redis/MySQL 职责

---

## 工作方式

每个 Milestone：

Plan
→ Implementation
→ Tests
→ Real acceptance
→ Acceptance report
→ Git commit

完成后停止。

不得自动进入下一 Milestone。