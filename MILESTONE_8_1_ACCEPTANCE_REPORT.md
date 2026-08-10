# Milestone 8.1 — Adaptive Basic RAG Acceptance Report

## Acceptance status

**PASS** — Adaptive Basic RAG is implemented, tested and committed on top of the M7 Reliability v2 baseline. All M1–M7 correctness is preserved (confirmed by the full default suite, M7 infra regression and M1–M6.6 infra regression).

**Closing verification (M8.1 final):** embedding configuration is now fail-fast (no silent real→Mock fallback), the RAG index staleness constraint is documented, and the **Real Provider Acceptance PASSED** with a real embedding API + Qdrant + a real LLM.

M8.1 adds an adaptive context strategy: short transcripts answer questions with the full transcript (DIRECT_CONTEXT), long transcripts use a real Basic RAG pipeline (chunking → embedding → Qdrant → top-K retrieval → LLM QA). Both modes return a grounded answer with **timestamp citations validated against real persisted transcript/chunk metadata** — the LLM never fabricates timestamps.

## 0. Closing items

### Embedding provider configuration is fail-fast

- `EMBEDDING_PROVIDER=mock` (or unset) → `MockEmbeddingProvider`.
- Explicit `openai` / `dashscope` → the provider **requires** `EMBEDDING_API_KEY`, `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL`, and a positive `EMBEDDING_DIMENSION` (the dimension check lives in `EmbeddingProperties` for real providers). Missing configuration raises a clear startup error naming the missing variable. There is **no** silent real→Mock fallback anymore.
- Tests: `EmbeddingProviderConfigurationTest` (mock on mock/blank; fail-fast for missing apiKey/baseUrl/model; fail-fast on invalid dimension; real built only when complete; unknown provider rejected).

### RAG index staleness — business constraint, no code change

`analysis_task` has `UNIQUE KEY uk_analysis_task_business (video_id, analysis_type, model_version)`, and `AnalysisTaskPersistenceService.createPending` refuses to create a second task for the same video+analysis type+model version (it returns `ANALYSIS_ALREADY_RUNNING` even for a previously FAILED task). Therefore **a video can never produce a new successful AnalysisTask or a new Transcript** after the original one. `video_rag_index.analysis_task_id` always refers to the video's one and only analysis task, and `findLatestSuccessfulByVideoId` resolves to that same task's segments. Consequently an old-task READY index can never be mistakenly used against a new transcript — the scenario cannot occur in this system. This is documented as a business constraint rather than patched with a versioning scheme.

### Real Provider Acceptance — PASS

Gated by `VIDEOAGENT_M8_REAL_AI_TEST=true` (default OFF). A real run used:

- Embedding: DashScope OpenAI-compatible `/compatible-mode/v1/embeddings`, `text-embedding-v3`, 1024 dimensions (key read from the local `.env`, never logged/committed).
- Vector store: Qdrant `video_transcript_chunks` (1024-dim collection).
- LLM: DeepSeek via the OpenAI-compatible LangChain4j chat model (existing `LLM_*` config).

A 60-segment long transcript was indexed (20 chunks). The question "为什么选择 Redis 作为任务进度缓存？" was asked; the Redis fact exists only in segment 50 (~50–51 s).

Human-verified result:

- **Retrieval found the correct chunk**: top-5 hits were `[16, 4, 18, 17, 11]`; chunk 16 covers segments 48–52 including the Redis fact.
- **Answer grounded**: the answer restated the transcript's Redis conclusion (读写延迟低、支持过期时间、多实例共享实时进度) — it came from the retrieved chunk, not outside knowledge.
- **Citation matches a real time range**: the returned citation was `[48000, 53000]` ms (48–53 s), which covers the Redis segment.
- **No hallucinated citation**: the timestamp came from Qdrant chunk metadata (derived from real transcript rows), not the LLM.
- **No cross-video/user content**: search is filtered by `userId + videoId`; the test used a single user/video.

Result: **`Milestone8RealAiInfrastructureSmokeTest` PASS (1/1)**, recorded as **Real Provider Acceptance PASS**.

Note: this run surfaced a real-provider detail fixed during acceptance — DashScope's embedding endpoint has a batch limit of 10, so `RealEmbeddingProvider.embedDocuments` now batches requests by 10 (the mock path is unaffected).

## 1. Why not every video uses RAG

RAG exists because long transcripts are impractical (or costly) to send in full to an LLM. For a short transcript, sending the entire context directly is:

- simpler (no embedding, no vector index, no retrieval recall loss, no vector-search cost);
- equally grounded (the model sees every segment);
- trivially citable (every segment index is real).

Only transcripts above `RAG_DIRECT_CONTEXT_MAX_CHARS` route to RAG. A short transcript never creates Qdrant vectors.

## 2. Why Transcript Size, not Video Duration

Video duration is a poor proxy: a 30-minute low-speech-density video can have a tiny transcript, while an 8-minute dense lecture can have a huge one. The property that actually determines how much context is practical is the transcript's total character count. `ContextStrategyResolver` therefore decides purely from `Σ segment.text.length()`. The unit test `shouldJudgeByTranscriptSizeNotVideoDuration` proves a tiny transcript on a "long" video is DIRECT while a huge transcript on a "short" video is RAG.

## 3. Adaptive Context Strategy

`QaContextMode` = `{DIRECT_CONTEXT, RAG}`. `ContextStrategyResolver` (component) decides:

```
transcriptChars <= RAG_DIRECT_CONTEXT_MAX_CHARS  -> DIRECT_CONTEXT
transcriptChars >  RAG_DIRECT_CONTEXT_MAX_CHARS  -> RAG
```

An empty transcript is a business error (`TRANSCRIPTION_FAILED`, "该视频暂无可用字幕，无法进行问答") rather than a meaningless QA.

## 4. DIRECT_CONTEXT architecture

```
JWT -> Ownership -> load transcript segments -> sort by segment_index
   -> build [SEGMENT i][startMs..endMs] context (index,text,startMs,endMs preserved)
   -> VideoQaProvider -> {answer, citationSegmentIndexes}
   -> backend maps indexes to real DB segments -> {answer, citations}
```

The context keeps each segment's identity (index + time range); it is never flattened into a plain string that drops timestamps. The unit tests confirm full segments are passed in order and that no embedding/Qdrant interaction happens in this mode.

## 5. RAG architecture

```
long transcript -> chunk -> embed -> Qdrant upsert (deterministic point ids)
question -> embedQuery -> Qdrant search (userId+videoId filter) -> top-K
   -> retrieved chunks as context -> VideoQaProvider -> {answer, citationChunkIndexes}
   -> backend maps indexes to real chunk metadata -> {answer, citations}
```

## 6. Direct/RAG threshold — engineering default, not theoretical optimum

`RAG_DIRECT_CONTEXT_MAX_CHARS=8000` is a deliberate engineering default. It is configurable and may later be tuned against the model context window, token counts, prompt cost and measured retrieval quality. No tokenizer was added: character count keeps the implementation minimal and explainable.

## 7. Chunking Strategy

`TranscriptChunker` builds `TranscriptChunk(chunkIndex, text, startMs, endMs, sourceSegmentIndexes)`:

- only adjacent transcript segments are combined;
- time order is preserved (input is re-sorted by segment_index/startMs);
- `chunk.startMs` = first segment start, `chunk.endMs` = last segment end;
- chunk size is bounded by `RAG_CHUNK_MAX_CHARS` (2000) with `RAG_CHUNK_OVERLAP_SEGMENTS` (1) overlap;
- no substring splitting that would break the time structure; a single segment larger than the cap still forms one chunk (never empty).

No semantic/LLM/embedding-boundary chunking in this milestone.

## 8. Embedding abstraction

`EmbeddingProvider` with `embedDocuments(texts)` / `embedQuery(text)`:

- `MockEmbeddingProvider` — deterministic, fixed vocabulary + hash tail, same text → same vector. Used for all tests and local dev.
- `RealEmbeddingProvider` — OpenAI-compatible HTTP `/embeddings`, fully configured by `EMBEDDING_PROVIDER/API_KEY/BASE_URL/MODEL/DIMENSION/TIMEOUT`. No API key/URL/model is hard-coded. Config is deliberately decoupled from the ASR/LLM providers.

`EmbeddingProviderConfiguration` falls back to Mock when a real provider is selected but missing configuration.

## 9. Qdrant design

- single-node Qdrant added to `docker-compose.yml` (REST port 6333, gRPC 6334) — no cluster/replication/sharding;
- collection `video_transcript_chunks`, dimension from `EMBEDDING_DIMENSION`;
- `QdrantVectorStore` is a minimal REST adapter (no new gRPC client dependency); it is a **derived vector index**, never a business database;
- deterministic point ids (`pointId(videoId, taskId, chunkIndex)` → unsigned 64-bit integer) so rebuilds replace rather than accumulate;
- `ensureCollection` only creates when missing.

## 10. Why Qdrant and not PostgreSQL + pgvector

VideoAgent's M1–M7 data layer is entirely MySQL. Adding PostgreSQL solely for pgvector would mean maintaining two relational databases, and migrating M1–M7 to PostgreSQL is not worth the migration/regression risk. The chosen split:

- **MySQL** → transactional / business data (user, video, task, outbox, transcript, summary, RAG index lifecycle);
- **Qdrant** → vector retrieval.

PostgreSQL + pgvector is recorded as the alternative considered if the project were greenfield on PostgreSQL.

## 11. Metadata schema

Each Qdrant chunk point carries payload: `userId`, `videoId`, `analysisTaskId`, `chunkIndex`, `text`, `startMs`, `endMs`, `sourceSegmentIndexes`. No JWT/API key/authorization data is ever stored.

## 12. userId + videoId isolation (double-layer)

1. REST layer enforces MySQL ownership (`VideoOwnershipService.requireOwned`) → foreign video is 404.
2. Vector layer enforces `userId + videoId` metadata filter on every search → even two users with near-identical transcripts cannot recall each other's chunks.

Verified by `RagControllerTest` (cross-user 404 for status/build/QA) and the infra test PATH B (foreign status/QA → 404, and A's Qdrant search returns only A's chunks).

## 13. RAG Index lifecycle

`video_rag_index` (Flyway V8), unique per video:

- statuses: `NOT_REQUIRED`, `NOT_BUILT`, `BUILDING`, `READY`, `FAILED` — a separate lifecycle from `analysis_task.status`;
- fields: `video_id`, `analysis_task_id`, `status`, `context_mode`, `transcript_chars`, `chunk_count`, `embedding_provider`, `embedding_model`, `embedding_dimension`, `last_error_code`, `last_error_message`, timestamps;
- `RagIndexService.buildIndex` is synchronous for M8.1 (limitation noted in §26).

## 14. NOT_REQUIRED semantics

Short transcripts → `mode=DIRECT_CONTEXT`, `status=NOT_REQUIRED`. Building such a video returns `NOT_REQUIRED` without calling embedding or Qdrant. The unit test `shouldNotBuildIndexForShortTranscript` asserts zero embedding/Qdrant interactions.

## 15. Rebuild / Idempotency

`buildIndex` → `claimBuilding` (concurrent builds prevented) → chunk → embed → **delete old vectors (userId+videoId) → upsert deterministic point ids** → `READY`. A READY/FAILED index can be rebuilt; old vectors are cleared first so rebuilds never duplicate. QA is rejected for anything not `READY`.

## 16. Top-K Retrieval

`TranscriptRetriever` embeds the question, searches Qdrant with the userId+videoId filter, and returns `RAG_TOP_K` (default 5) chunks ordered by score. No rerank/hybrid/multi-query in this milestone. A `RAG_MIN_SCORE` threshold is intentionally not invented (§26).

## 17. Grounded QA

Both modes prompt the model to answer **only from the provided transcript context** and to return `根据当前视频内容无法确定。` when the context is insufficient — no web, outside knowledge, or model memory presented as video content. The Mock QA provider implements the same grounded contract.

## 18. Direct Citation Validation

The model returns `citationSegmentIndexes`; the backend resolves each against the segments actually provided, producing `startMs/endMs/text` from the database. Indexes that do not exist are **dropped**. `shouldDropHallucinatedSegmentCitation` verifies a fabricated index (99) is discarded.

## 19. RAG Citation Validation

The model returns `citationChunkIndexes`; the backend resolves each against the **retrieved** top-K chunks' metadata. Indexes not retrieved are **dropped** (`shouldRejectCitationOutsideRetrievedChunks`). Timestamps always come from persisted chunk metadata, never the LLM.

## 20. Delete / Qdrant cleanup

`VideoService.deleteVideo` still commits the MySQL delete first, then best-effort removes MinIO object and calls `RagCleanupService.cleanupVideo(userId, videoId)` → `QdrantVectorStore.deleteByVideo(userId, videoId)`. A Qdrant failure logs a warning and never rolls back the committed business delete. Verified by `shouldDeleteStorageObjectAfterDatabaseDeletionAndTolerateCleanupFailure`.

## 21. MySQL / Qdrant consistency boundary

There is no MySQL+Qdrant atomic transaction. MySQL is the lifecycle source of truth; Qdrant holds derived vectors. MySQL may say READY while Qdrant is incomplete, or Qdrant may hold partial vectors while MySQL ends FAILED. Rebuild (delete + recreate) converges to consistency. No XA/2PC/Seata.

## 22. Unit tests

New M8.1 unit tests (all pass under `mvn test`, no paid providers):

| Area | Tests |
| --- | --- |
| Context strategy | `ContextStrategyResolverTest` (below/equal/above threshold, transcript-size-not-duration, empty transcript, char counting) |
| Chunking | `TranscriptChunkerTest` (single, multi, max chars, overlap, timestamp, ordering, empty, oversized segment) |
| Mock embedding | `MockEmbeddingProviderTest` (determinism, dimension, vocabulary positions, RocketMQ-beats-Redis for async question, distinct infra texts) |
| QA service | `VideoQaServiceTest` (direct: full segments passed, no embedding/qdrant, citation mapping, hallucination dropped, grounded fallback, empty transcript; RAG: not-ready rejected, retrieval, citation validation, fallback) |
| RAG index | `RagIndexServiceTest` (short→NOT_REQUIRED no vectors, long→READY, deterministic point ids, metadata on upsert, failure→FAILED, rebuild no duplicates, requireReady) |
| Retrieval | `TranscriptRetrieverTest` (query embed, top-K, userId+videoId filter, ordering, empty) |
| Controllers/security | `RagControllerTest` (status/build/QA OK, blank question 400, cross-user 404, RAG_INDEX_NOT_READY conflict) |

Total default suite: **215 tests, 0 failures, 0 errors, 26 skipped** (skips are env-gated infra/real-provider tests).

## 23. Infrastructure Acceptance

`VIDEOAGENT_M8_RAG_INFRA_TEST=true` uses real MySQL, Redis, RocketMQ, MinIO, FFmpeg and **Qdrant** with Mock ASR/Summary/Embedding/QA. Result: **2/2 passed** on a clean Flyway V1→V8 database.

- **PATH A (short transcript):** status DIRECT_CONTEXT/NOT_REQUIRED; build returns NOT_REQUIRED with `chunk_count=0` (no vectors); QA answers grounded with timestamp citations.
- **PATH B (long transcript):** status RAG/NOT_BUILT → build → READY with `chunk_count>0`; QA in RAG mode with citations; foreign user gets 404 for status and QA; A's Qdrant search only returns A's chunks.

## 24. Real Provider Acceptance

`VIDEOAGENT_M8_REAL_AI_TEST=true` (default **OFF**) gates `Milestone8RealAiInfrastructureSmokeTest`, which drives the real embedding + real LLM path entirely from environment variables. It never runs under `mvn test`. Manual acceptance requires confirming the answer is grounded in the transcript, citations fall within real time ranges, and no cross-video data or fabricated citations appear.

**Real run result: PASS (1/1).** See section 0 for the full human-verified evidence (retrieval hit, grounded answer, `[48000,53000]` citation, no hallucination, no cross-video leakage).

## 25. M1–M7 Regression

- Backend default suite: **215 tests, 0 failures**.
- M7 infra regression (`VIDEOAGENT_M7_INFRA_TEST=true`): **9/9 passed** — transactional outbox atomicity, retry rollback, fencing, max attempts, starvation, terminal events all still green.
- M1–M6.6 infra regression (Mock providers): **9/9 passed**.
- M8 RAG infra (`VIDEOAGENT_M8_RAG_INFRA_TEST=true`): **2/2 passed**.
- Flyway V1→V8 clean migration: **passed** on a fresh MySQL database.
- Frontend `vue-tsc` + `tsc` + Vite build: **passed**.
- JWT/ownership/Video CRUD/MinIO/RocketMQ/Redis/SSE/outbox/claim/retry/fencing/stale-recovery/durable-resume remain unchanged.

## 26. Known Limitations

- **Rule-based strategy**: only transcript size decides DIRECT vs RAG. This is not an agent, LLM intent classifier, or tool router.
- **RAG is basic**: dense retrieval, single query, top-K only. No query rewrite, multi-query, hybrid/BM25, reranker, agent routing, Self-RAG or CRAG.
- **DIRECT_CONTEXT tradeoff**: as transcripts grow, prompts lengthen, cost rises and model attention may degrade — this is part of the motivation for M8.2 agentic retrieval.
- **RAG tradeoffs**: retrieval miss, chunk-boundary effects, top-K sensitivity, embedding-quality dependence.
- **No RAG_MIN_SCORE**: top-K only; a score threshold is not invented without evidence.
- **Synchronous index build**: a very long index build blocks the request. If real use shows this is unacceptable, a background build would be the next step (noted, not implemented).
- **No MySQL+Qdrant atomicity**: cross-store eventual consistency by design; rebuild converges.
- **Qdrant deletion is best-effort** after the MySQL video delete commits; an orphan vector is possible but unreachable through any ownership-checked API.

## Git

- Branch: `feature/m8-adaptive-rag` (created from the M7 v2 head).
- Commit: `feat: complete milestone 8.1 adaptive rag`.
- `git diff --check` clean; `.env` untracked; no real secrets in tracked sources.

## Explicit non-claims

- Not every video uses RAG; short transcripts are DIRECT_CONTEXT.
- No M8.2 agent/tool-router/MCP/memory work was started.
- No PostgreSQL/pgvector, Elasticsearch, Milvus, Weaviate or Pinecone.
- No distributed transaction between MySQL and Qdrant.
