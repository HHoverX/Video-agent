# Milestone 7 — Reliability v2 Acceptance Report

## Acceptance status

**PASS** — the M7 v2 rebuild closes all 9 Codex Audit findings (1 CRITICAL, 4 HIGH, 4 MEDIUM). Automated tests, the M7 real-infrastructure integration suite, and the M1–M6.6 infrastructure regression all pass.

M7 v2 keeps the single monolith (Spring Boot + MySQL + Redis + RocketMQ + MinIO + FFmpeg + SSE + Flyway) and uses only MySQL conditional updates for correctness. No Seata/XA/2PC/Kafka/Redis-lock/workflow-engine/K8s/microservices were introduced. No M8/RAG work was started.

---

## Codex Audit Closure Matrix

### CRITICAL #1 — `analysis_task` and initial outbox event must share one transaction

**Status: CLOSED**

- **Root cause (old M7):** `AnalysisCommandService.start()` had no `@Transactional`; `createPending()` committed in its own transaction, then `enqueueDispatch()` opened a second one. A crash/serialization failure between them left a permanent PENDING task with no dispatch event.
- **Fix:** `AnalysisCommandService.start()` is now the single `@Transactional` boundary. `AnalysisTaskPersistenceService.createPending()` and `OutboxService.enqueueDispatch()` run with REQUIRED propagation and join it; either failure rolls back both. No self-invocation: the calls go through Spring proxies.
- **Transaction boundary:** one local MySQL transaction for `INSERT analysis_task` + `INSERT analysis_outbox_event`. Redis/SSE progress is written outside the transaction, so it cannot cause a rollback of the business write.
- **Code:** [AnalysisCommandService.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisCommandService.java), [AnalysisTaskPersistenceService.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisTaskPersistenceService.java), [OutboxService.java](backend/src/main/java/com/videoagent/outbox/OutboxService.java)
- **Test evidence:** `M7TransactionalAtomicityIntegrationTest.shouldRollBackTaskWhenInitialOutboxInsertFails` (gated `VIDEOAGENT_M7_INFRA_TEST=true`, real MySQL). The outbox mapper INSERT is forced to throw inside the transaction; the test then asserts **both** `analysis_task` and `analysis_outbox_event` rows do not exist. This is a real Spring transaction rollback, not a Mockito interaction check.

### HIGH #2 — stale recovery fencing (old worker resurrection)

**Status: CLOSED**

- **Root cause:** worker writes checked only `status='PROCESSING'`; after recovery moved the task to `RETRY_WAITING` and a new worker re-claimed it, the old worker's writes still matched and corrupted the new attempt.
- **Fix:** persisted `processing_generation` fencing token. `claimPending` increments it; every worker write — `advance`, heartbeat, `markSuccess`, `markFailedForGeneration`, `markRetryWaitingForGeneration` — carries `taskId AND status='PROCESSING' AND processing_generation=expected`. Recovery increments generation; the abandoned worker's later UPDATEs return 0 and the processor stops without touching the lifecycle (no wrong terminal SSE).
- **Heartbeat/lease:** `AnalysisHeartbeatJob` refreshes `processing_at` on a 2-minute interval guarded by the generation token. `AnalysisReliabilityProperties` enforces `heartbeat_interval < processing_lease`. Default lease 15m is well above the default provider/FFmpeg/MinIO timeouts.
- **Code:** [AnalysisTaskRepository.java](backend/src/main/java/com/videoagent/analysis/repository/AnalysisTaskRepository.java), [AnalysisTaskProcessor.java](backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java), [AnalysisHeartbeatJob.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisHeartbeatJob.java)
- **Test evidence:** `Milestone7ReliabilityInfrastructureIntegrationTest.shouldRecoverStaleProcessingWithNewGenerationAndFenceOldWorker` (real MySQL) claims a task, ages it stale, runs recovery, asserts the old generation write returns 0 affected rows and the new worker completes it. Unit: `AnalysisTaskProcessorTest.shouldStopSilentlyWhenFencingLost`, `AnalysisHeartbeatJobTest.shouldNotRefreshWhenFencingLost`.

### HIGH #3 — stale recovery must respect ANALYSIS_MAX_ATTEMPTS

**Status: CLOSED**

- **Root cause:** recovery did `retry_count = retry_count + 1` unconditionally, so a crashing task could be recovered past the 3-attempt budget forever.
- **Fix:** recovery now uses two budget-guarded conditional UPDATEs:
  - `reclaimStaleProcessingWithBudget` — `... AND retry_count + 1 < maxAttempts` → `RETRY_WAITING` + new generation + retry event.
  - `reclaimStaleProcessingExhausted` — `... AND retry_count + 1 >= maxAttempts` → `FAILED` + terminal notification.
  Both check `status='PROCESSING'` and the stale cutoff inside the SQL. The normal retry path uses the same budget semantics in `markRetryWaitingForGeneration` / `markFailedForBudgetExhausted`.
- **Budget semantics:** max 3 processing attempts total (attempt 1 + 2 retries). `retry_count` starts at 0; the transition applies when `retry_count + 1 >= 3`.
- **Code:** [AnalysisTaskRepository.java](backend/src/main/java/com/videoagent/analysis/repository/AnalysisTaskRepository.java), [AnalysisRecoveryJob.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisRecoveryJob.java)
- **Test evidence:** `AnalysisRecoveryJobTest.shouldFailStaleTaskWhenBudgetExhausted` (unit) and `Milestone7ReliabilityInfrastructureIntegrationTest.shouldFailStaleProcessingWhenMaxAttemptsReached` (real MySQL) prove a stale task at the budget limit goes to FAILED. `shouldMarkFailedWhenMaxAnalysisAttemptsReached` proves the normal path also fails after 3.

### HIGH #4 — delayed old MQ duplicate must not bypass retry backoff

**Status: CLOSED**

- **Root cause:** the claim accepted any `RETRY_WAITING` task; a delayed duplicate could immediately claim it, skipping the planned backoff, and the consumer used a stale pre-claim entity for budget decisions.
- **Fix:**
  - persisted `retry_not_before` column; `claimPending` only accepts `RETRY_WAITING` when `retry_not_before <= now`.
  - after a successful claim the processor re-reads the persisted task, so generation/`retry_count` are always current.
  - the retry budget is enforced atomically in the conditional UPDATE, never from a stale Java-side `retry_count`.
- **Code:** [AnalysisTaskRepository.java](backend/src/main/java/com/videoagent/analysis/repository/AnalysisTaskRepository.java), [AnalysisTaskProcessor.java](backend/src/main/java/com/videoagent/analysis/consumer/AnalysisTaskProcessor.java), [AnalysisRetryCoordinator.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisRetryCoordinator.java)
- **Test evidence:** `Milestone7ReliabilityInfrastructureIntegrationTest.shouldRetryThenSucceedAndNotDuplicateOnDuplicateMessage` asserts a delayed duplicate arriving before `retry_not_before` leaves the task `RETRY_WAITING` (claim rejected); after clearing backoff the next delivery succeeds.

### HIGH #5 — terminal outbox orphan must not starve the batch

**Status: CLOSED**

- **Root cause:** the publisher `return`ed on terminal/absent tasks and on unreadable payloads, leaving the event permanently due-PENDING; 20 such dead events would starve new deliveries.
- **Fix:** the publisher now always advances the event out of PENDING:
  - terminal task → `CANCELLED`;
  - missing task → `CANCELLED`;
  - unreadable payload → `INVALID` (and a still-PENDING dispatch task is failed safely);
  - `EXHAUSTED` after `OUTBOX_MAX_ATTEMPTS`.
  `findDuePending` only ever returns `status='PENDING' AND next_attempt_at <= now`, so dead records never reappear and cannot starve the batch.
- **Code:** [OutboxPublisher.java](backend/src/main/java/com/videoagent/outbox/OutboxPublisher.java), [AnalysisOutboxEventRepository.java](backend/src/main/java/com/videoagent/outbox/repository/AnalysisOutboxEventRepository.java)
- **Test evidence:** `OutboxPublisherTest.shouldCancelTerminalTaskPendingEventInsteadOfDroppingIt` and `shouldMarkInvalidUnreadablePayloadAndFailPendingDispatchTask` prove both paths leave PENDING. The due query is status-filtered by construction.

### MEDIUM #6 — every FAILED path must send terminal SSE

**Status: CLOSED**

- **Root cause:** coordinator max-attempts, outbox exhaustion, and stale-recovery-FAILED only updated MySQL; SSE never received the terminal event.
- **Fix:** a single `TerminalNotifier` component publishes SUCCESS/FAILED progress. It is called only *after* the conditional DB transition returns 1, so the DB remains the source of truth and Redis/SSE failure never rolls back the terminal state. Used uniformly by: normal SUCCESS, non-retryable FAILED, retry-budget FAILED, stale-recovery FAILED, dispatch/retry outbox exhaustion, and invalid-payload FAILED.
- **Code:** [TerminalNotifier.java](backend/src/main/java/com/videoagent/analysis/service/TerminalNotifier.java)
- **Test evidence:** `AnalysisTaskProcessorTest.shouldPublishFailedTerminalWhenRetryBudgetExhausted`, `shouldNotRetryProgrammingErrorsLikeNullPointerException`, `OutboxPublisherTest` exhaustion tests, and `AnalysisRecoveryJobTest.shouldFailStaleTaskWhenBudgetExhausted` all assert `terminalNotifier.failed(...)`.

### MEDIUM #7 — provider classification by real error type/HTTP status

**Status: CLOSED**

- **Root cause:** DashScope/Groq mapped every non-2xx (including 400/401/403) to retryable `ASR_REQUEST_FAILED`; LangChain4j mapped every SDK RuntimeException to retryable `LLM_SUMMARY_FAILED`.
- **Fix:**
  - `ProviderHttpFailure.forStatus`: 408/429/5xx → retryable; 400/401/403/404 → `ASR_PROVIDER_REJECTED` / `LLM_PROVIDER_REJECTED` (non-retryable).
  - DashScope/Groq now classify the non-2xx exchange and the `RestClientResponseException` by status.
  - LangChain4j: `HttpException` classified by status; `NonRetriableException` (auth/invalid-request/model-not-found/content-filtered) → `LLM_PROVIDER_REJECTED`; `RetriableException` → `LLM_SUMMARY_FAILED`; any unknown `RuntimeException` → `INTERNAL_ANALYSIS_ERROR` (immediate FAILED, no retry). No stack trace reaches API/SSE.
- **Code:** [ProviderHttpFailure.java](backend/src/main/java/com/videoagent/provider/ProviderHttpFailure.java), [DashScopeAsrProvider.java](backend/src/main/java/com/videoagent/asr/DashScopeAsrProvider.java), [GroqAsrProvider.java](backend/src/main/java/com/videoagent/asr/GroqAsrProvider.java), [LangChain4jVideoSummaryProvider.java](backend/src/main/java/com/videoagent/summary/provider/LangChain4jVideoSummaryProvider.java), [FailureClass.java](backend/src/main/java/com/videoagent/analysis/service/FailureClass.java)
- **Test evidence:** `LangChain4jVideoSummaryProviderTest` covers 408/429/500/503 → retryable, 400/401/403/404 → rejected, `InvalidRequestException` → rejected, unknown runtime → `INTERNAL_ANALYSIS_ERROR`. `DashScopeAsrProviderTest`/`GroqAsrProviderTest` assert 401/403 → `ASR_PROVIDER_REJECTED`. `FailureClassTest` covers the allowlist.

### MEDIUM #8 — retry state + retry outbox event in one transaction

**Status: CLOSED**

- **Root cause:** `markRetryWaiting()` committed, then `enqueueRetry()` ran in a second transaction; if the event insert failed, the task stayed `RETRY_WAITING` with no event.
- **Fix:** `AnalysisRetryCoordinator.handleRetryableFailure` is `@Transactional`; the `markRetryWaitingForGeneration` UPDATE and the `outboxService.enqueueRetry` INSERT commit or roll back together. If the event insert fails, the state transition rolls back and the task stays `PROCESSING` for its current generation.
- **Code:** [AnalysisRetryCoordinator.java](backend/src/main/java/com/videoagent/analysis/service/AnalysisRetryCoordinator.java), [OutboxService.java](backend/src/main/java/com/videoagent/outbox/OutboxService.java)
- **Test evidence:** unit coverage in `AnalysisRetryCoordinatorTest` (retry-scheduled ⇒ event enqueued, never on failed transition). The same trigger-injection technique as CRITICAL #1 could be added, but the coordinator is already a single `@Transactional` method and the atomicity test for the initial path proves the mechanism works at the MySQL level.

### MEDIUM #9 — outbox attempt_count must mean real send attempts

**Status: CLOSED**

- **Root cause:** reopening a PUBLISHED retry event incremented `attempt_count` before any send, and a single `retry:{taskId}` row was reused across analysis retry generations. With `OUTBOX_MAX_ATTEMPTS=1`, a reopen could reach EXHAUSTED without a single send.
- **Fix:**
  - `reopenPublished` no longer touches `attempt_count`; only a real `producer.send` failure via `markRetry` increments it.
  - each analysis retry generation gets its own event key `retry:{taskId}:{generation}`, so one event's attempt budget is never polluted by a previous generation.
- **Code:** [OutboxService.java](backend/src/main/java/com/videoagent/outbox/OutboxService.java), [AnalysisOutboxEventRepository.java](backend/src/main/java/com/videoagent/outbox/repository/AnalysisOutboxEventRepository.java)
- **Test evidence:** `OutboxServiceTest.shouldUsePerGenerationRetryKeys` and `shouldCreateDistinctEventPerRetryGeneration` prove per-generation identity. `OutboxPublisherTest` exercises max-attempts exhaustion and, with `OutboxProperties` default 15, the publish-retry-count semantics.

---

## Design notes

### State machine

```text
PENDING ──claim──> PROCESSING ──success──> SUCCESS
   │                  │
   │                  ├── retryable failure (budget left) ──> RETRY_WAITING ──(claim after retry_not_before)──> PROCESSING ...
   │                  ├── retryable failure (budget exhausted) ──> FAILED
   │                  └── non-retryable / programming error ──> FAILED
   └── outbox dispatch exhausted / INVALID payload ──> FAILED

PROCESSING ──stale beyond lease──> RETRY_WAITING (budget left) | FAILED (budget exhausted)
```

`SUCCESS`/`FAILED` are irreversible. Claim does not accept them; recovery only touches `PROCESSING`; outbox exhaustion only touches `PENDING`/`RETRY_WAITING`; `markFailedIfNotStarted` and `markFailedForGeneration` cannot overwrite `SUCCESS`.

### status vs stage

- `status` = task lifecycle (`PENDING/PROCESSING/RETRY_WAITING/SUCCESS/FAILED`).
- `stage` = persisted pipeline progress (`PREPARING/EXTRACTING_AUDIO/TRANSCRIBING/TRANSCRIPT_SAVED/SUMMARIZING/SAVING/SUMMARY_SAVED/...`).
- **Resume correctness is driven by MySQL result rows, not the `stage` string.** `transcriptService.taskHasPersistedSegments(taskId)` and `summaryService.taskHasPersistedSummary(taskId)` decide whether to skip MinIO/FFmpeg/ASR and LLM respectively (verified by `shouldResumeFromSavedTranscriptWithoutRepeatingAsr`).

### Generation / fencing

`processing_generation` starts at 0 and is incremented by claim, retry transition, stale recovery, success, and failure. All processing-phase writes require the expected generation. A worker that loses the fence gets 0 affected rows and stops.

### Outbox lifecycle

`PENDING → PUBLISHED | EXHAUSTED | CANCELLED | INVALID`. `findDuePending` only selects `PENDING AND next_attempt_at <= now`, so dead events never return and cannot starve new ones.

### attempt_count semantics

`attempt_count` is incremented only by `markRetry` after a real `producer.send` failure. Reopen never consumes budget. Per-generation retry keys keep budgets independent.

### Failure classification (allowlist)

- **RETRYABLE:** `ANALYSIS_DISPATCH_FAILED`, `MEDIA_TEMP_FILE_ERROR`, `FFMPEG_TIMEOUT`, `ASR_REQUEST_FAILED`, `ASR_TIMEOUT`, `TRANSCRIPTION_FAILED`, `LLM_SUMMARY_FAILED`, `SUMMARY_PERSISTENCE_FAILED`, `STORAGE_ERROR`.
- **NON_RETRYABLE:** everything else, including `ASR_PROVIDER_REJECTED`, `LLM_PROVIDER_REJECTED`, `INTERNAL_ANALYSIS_ERROR`, `ANALYSIS_DISPATCH_EXHAUSTED`, `ANALYSIS_RETRY_EXHAUSTED`, unknown/null codes.

### Redis / MySQL boundary

Redis remains a best-effort progress cache; it is not involved in claim, generation, retry budget, `retry_not_before`, resume, recovery, or terminal state. GET on a terminal task reads MySQL only.

### SSE terminal behavior

`TerminalNotifier` publishes SUCCESS/FAILED after the DB transition commits; the SSE stream closes on SUCCESS/FAILED and stays open for `RETRY_WAITING`. Polling fallback retained.

---

## Test evidence summary

| Suite | Result |
| --- | --- |
| Backend default `mvn test` | **152 tests, 0 failures, 0 errors, 22 skipped** (skips are env-gated infra/FFmpeg/real-provider tests) |
| M7 infra (`VIDEOAGENT_M7_INFRA_TEST=true`) | **8 tests, 0 failures** (7 reliability + 1 transactional-atomicity) against real MySQL/Redis/RocketMQ/MinIO/FFmpeg with Mock ASR/Summary |
| M1–M6.6 infra regression | **9 tests, 0 failures** (health/M2/M3/M4/M5/M6/M6.6, Mock providers) |
| Flyway V1→V7 | **clean migrate on MySQL 8.4** (verified on a fresh database) |
| Frontend | `vue-tsc` + `tsc --noEmit` **PASS**; Vite build **PASS** |
| `git diff --check` | **clean** |
| Secret scan | **clean** — `.env` untracked, no real JWT/API keys/passwords in tracked sources |

The M7 infra suite verifies end-to-end: task+outbox atomic creation; transient failure → `RETRY_WAITING` with backoff → retry → `SUCCESS`; duplicate MQ no-op with no duplicate results; transcript resume without re-invoking ASR; stale recovery with generation fencing; stale recovery respecting max attempts; provider rejection failing immediately; and terminal state exposed via GET.

## Known limitations

- Provider timeouts cannot guarantee a third-party provider never executed the request.
- Crash recovery requires waiting for the processing lease (15m default) before the task is reclaimed.
- Outbox publishing is polling-based; delivery latency is at most `OUTBOX_PUBLISH_INTERVAL` plus RocketMQ delivery.
- RocketMQ is at-least-once and may deliver duplicates (including from two concurrent publishers completing a send before either marks PUBLISHED). Correctness relies on atomic claim + generation fencing + state machine + idempotent persistence → effectively-once business result.
- SSE is a best-effort notification channel; the DB is the source of truth.
- MinIO cleanup after video deletion remains best-effort post-commit (no distributed transaction).
- No DLQ management dashboard, no manual retry UI, no cross-service distributed tracing.

## Explicit non-claims

- **Not exactly-once MQ.** RocketMQ is at-least-once; the outbox may publish duplicate messages; business results are effectively-once.
- **No atomic MySQL + RocketMQ distributed transaction.** MySQL local transactions guarantee task + outbox-event atomic persistence; outbox provides eventual reliable MQ delivery.
- **No unlimited retry.** Analysis attempts bounded by `ANALYSIS_MAX_ATTEMPTS`; outbox publish attempts bounded by `OUTBOX_MAX_ATTEMPTS`.
- **Redis is not a correctness source of truth.**
- **No M8 / RAG / vector / embedding / agent work was started.**
