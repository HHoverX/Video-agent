# Milestone 6.6 Acceptance Report

## Acceptance status

**PASS — implementation, automated tests, real infrastructure tests, authenticated API E2E, browser manual acceptance, and the correctly configured real AI provider chain all passed.**

The user completed the remaining local browser acceptance after the automated and infrastructure suites passed. Registration, login, upload, paging/query, title update, delete, analysis, authenticated SSE progress, Transcript/Summary results, and the correctly configured DashScope + DeepSeek real AI chain were confirmed in the browser. A previously observed DashScope request failure was traced to missing local PowerShell ASR environment configuration rather than a code defect.

## 1. Git baseline

- Branch: `main`.
- M6.5 baseline commit: `13f0daa feat: complete milestone 6.5 real ai integration`.
- Previous M6 checkpoint: `e2d1b98 feat: complete milestone 6 sse realtime progress`.
- M6.5 and M6.6 changes are not mixed: M6.5 was committed before M6.6 implementation began.
- No historical Flyway migration was modified. M6.6 adds only V5.

## 2. User schema

Migration: `V5__create_app_user_and_video_ownership.sql`.

`app_user` contains only:

- `id BIGINT UNSIGNED` primary key;
- `username VARCHAR(50)` with a unique key and nonblank check;
- `password_hash VARCHAR(100)` with a nonblank check;
- `created_at` and `updated_at`.

Passwords are persisted only as BCrypt hashes. No nickname, avatar, email, phone, role, or permission model was added.

The migration also adds `idx_video_user_created (user_id, created_at, id)` for owner-scoped descending list queries. It intentionally does not backfill historical `video.user_id`, assign old videos to new users, or add a foreign key that would invalidate existing nullable historical ownership. Historical rows whose `user_id` does not match the authenticated user remain invisible.

## 3. Authentication flow

- `POST /api/auth/register` validates username length 3–50 and password length 8–72, rejects duplicate usernames with `USERNAME_ALREADY_EXISTS`, and stores a BCrypt hash.
- `POST /api/auth/login` compares the submitted password with BCrypt and returns a JWT plus basic user data. Invalid username and invalid password share `INVALID_CREDENTIALS` to avoid account enumeration.
- `GET /api/auth/me` resolves the current user exclusively from `SecurityContext`; the client never submits `userId`.
- DTOs and API responses never expose `password` or `password_hash`.

## 4. JWT flow

- JJWT verifies HMAC signature and expiration.
- Claims include subject/user id, `userId`, `username`, issued-at, and expiration.
- `JWT_SECRET` and `JWT_EXPIRATION` come from configuration/environment variables.
- `JwtAuthenticationFilter` extends `OncePerRequestFilter`, validates `Authorization: Bearer ...`, and writes an `AuthenticatedUser` principal into a fresh `SecurityContext`.
- Authentication is stateless with `SessionCreationPolicy.STATELESS`.
- Invalid, missing, or expired credentials return structured HTTP 401 responses without exposing parser or signature details.

Explicit M6.6 boundary: there is no refresh token, server-side token blacklist, token revocation endpoint, Redis session, or multi-device session management. A signed JWT remains valid until expiration. Frontend logout deletes only local authentication state.

## 5. Security filter chain

Public requests are limited to registration, login, `/api/health`, and actuator health. Other application APIs require authentication.

CSRF, form login, HTTP Basic, server logout, and request caching are disabled for the stateless Bearer-token API. The internally generated Spring Boot default user is explicitly excluded because it is not part of the JWT design. Internal ASYNC and ERROR servlet redispatches are permitted so authenticated SSE continuations and structured SSE error responses are not rewritten after the original request has already passed authentication and ownership checks.

There is no role, RBAC, ACL, IAM, or administrator layer. The implemented boundary is stateless authentication plus video-level resource ownership.

## 6. Video ownership

Upload writes `video.user_id` from the authenticated principal. Every video lookup uses both `video.id` and the current user id.

Ownership is enforced for:

- video list, detail, title update, and delete;
- analysis creation;
- analysis task query and SSE subscription through `task -> video -> video.user_id`;
- transcript, summary, chapters, and key points.

A caller who knows another user's `videoId` or `taskId` receives HTTP 404, preventing resource-existence disclosure.

## 7. Pagination and fuzzy query

`GET /api/videos?page=1&size=10&keyword=agent` uses MyBatis-Plus pagination.

- Defaults: page 1, size 10.
- Maximum size: 50.
- Keyword is optional, trimmed, and applied only to `title`.
- Query is always owner-scoped.
- Sort order is `created_at DESC, id DESC` for deterministic ties.
- Response contains `items`, `page`, `size`, `total`, and `pages`.

Current project scale uses MySQL `LIKE '%keyword%'`. No misleading normal B-tree title index was added for a leading-wildcard query. Full-text search or Elasticsearch may be evaluated only if later scale and relevance-ranking requirements justify it; Elasticsearch is not part of M6.6.

## 8. Update behavior

`PATCH /api/videos/{videoId}` accepts only `title`.

- Owner-only.
- Whitespace is trimmed.
- Valid length is 1–255 characters.
- `user_id`, object key, status, file, and all other metadata are immutable through this endpoint.
- Successful response returns the updated DTO rather than exposing an entity.

## 9. Delete consistency strategy

`DELETE /api/videos/{videoId}` returns 204 on success.

Within a MySQL transaction it:

1. locks and verifies the owner-scoped video row;
2. rejects deletion with `VIDEO_ANALYSIS_IN_PROGRESS` and HTTP 409 if a PENDING or PROCESSING task exists;
3. deletes analysis tasks, allowing existing task foreign-key cascades to remove transcript, summary, chapter, and key-point rows;
4. deletes the video row;
5. commits the database transaction.

After commit, MinIO object removal is best effort. A MinIO failure records a warning but does not restore business rows or expose storage internals to the caller. MySQL and MinIO do not share an atomic transaction; a cleanup failure may leave an orphan object, while avoiding database rows that point to a deleted object. No distributed transaction, Seata, outbox, or compensation subsystem was introduced.

## 10. SSE JWT adaptation

The browser's native `EventSource` was replaced with a small `fetch` + `ReadableStream` SSE client so the request sends `Authorization: Bearer <JWT>` without putting tokens in URLs.

The parser handles SSE frame boundaries, `event: progress`, multi-line data, terminal SUCCESS/FAILED closure, `AbortController` component cleanup, and the existing polling fallback. The server validates task ownership before creating the emitter. Structured JSON errors explicitly use `application/json`, including when the request advertises `text/event-stream`, so a cross-user SSE attempt returns a stable 404 instead of an authentication/error-dispatch artifact.

## 11. Backend tests

Default suite result:

- 44 suites;
- 112 tests discovered;
- 98 passed;
- 14 skipped by explicit infrastructure/real-provider environment gates;
- 0 failures and 0 errors.

Coverage includes registration, duplicate username, BCrypt persistence, login, `/me`, JWT expiration, missing/invalid authentication, owner-scoped upload/list/detail/update/delete, pagination query construction, cross-user analysis/transcript/summary/task/SSE access, deletion blocking/cascade boundary, MinIO best-effort behavior, authenticated SSE, terminal closure, and polling fallback.

`Milestone66InfrastructureIntegrationTest` is gated by `VIDEOAGENT_M66_INFRA_TEST=true` and passed against real MySQL and MinIO. It verifies two-user isolation, paging totals/order, title LIKE search, title update, active-task delete blocking, completed-result cascade deletion, MinIO cleanup, and cross-user 404s for every derived resource.

`AnalysisSseInfrastructureIntegrationTest` passed with authentication against real MySQL, Redis, MinIO, RocketMQ NameServer/Broker, FFmpeg, Mock ASR, and Mock Summary.

The real paid AI test remains gated by `VIDEOAGENT_REAL_AI_TEST=true`; it was not executed during M6.6. Its upload, analysis, task query, and result-query requests now register/login and carry a JWT rather than disabling Security.

## 12. Frontend build

Passed:

- `vue-tsc --noEmit -p tsconfig.app.json --incremental false`;
- `tsc --noEmit -p tsconfig.node.json --incremental false`;
- Vite production build with 1,685 transformed modules.

Frontend additions include login/register pages, Pinia auth state, localStorage token persistence, Axios request/401 interceptors, Router Guards, logout, paged/searchable video list, title editing, confirmed deletion, active-analysis delete messaging, and authenticated fetch-based SSE.

M6.6 permits localStorage for the JWT, but this has an XSS tradeoff. A production deployment may later evaluate HttpOnly/SameSite cookie authentication according to its deployment and CSRF model. M6.6 does not switch to cookie auth.

## 13. Browser manual test

The user manually confirmed all required local browser flows:

- user registration and login;
- authenticated MP4 upload;
- video paging and title query;
- title update and confirmed deletion;
- the complete video analysis workflow;
- authenticated SSE real-time progress;
- Transcript, Overview, Chapters, and Key Points rendering;
- DashScope real ASR and DeepSeek real structured summary when the required local environment variables are configured.

The earlier DashScope ASR request failure was reproduced only when the local PowerShell session lacked the required ASR environment variables. With the correct environment configuration, the real provider chain passed; no code change was needed for that incident.

The separate authenticated API E2E also passed:

- User A and User B registration/login and `/me`;
- two authenticated MP4 uploads;
- page size/total/pages, title search, and rename;
- User B list total 0 and 404 for User A detail, analysis start, transcript, summary, and task;
- authenticated analysis creation and SSE;
- observed SSE stages: PREPARING, EXTRACTING_AUDIO, TRANSCRIBING, SAVING_TRANSCRIPT, SUMMARIZING, SAVING, DONE;
- terminal SUCCESS/100 and GET polling fallback;
- 3 transcript segments, 2 chapters, and 3 key points;
- idle-video deletion and completed-result deletion.

All temporary M6.6 acceptance videos and prefixed test users were removed after verification.

## 14. Known limitations

- JWT has no refresh token, blacklist, or active server-side revoke-before-expiration mechanism.
- localStorage JWT storage has an XSS tradeoff.
- MySQL and MinIO deletion cannot be one atomic transaction.
- MinIO cleanup failure can leave an orphan object and is currently handled by warning-only best effort.
- `LIKE '%keyword%'` is suitable for the present project scale, not large-scale relevance search.
- Historical videos with null or unmatched `user_id` are intentionally invisible.
- No RBAC, role/permission tables, ACL, OAuth2, SSO, Elasticsearch, WebSocket, RAG, or M7 reliability features were added.

## 15. Secret check

- `.env` is ignored and not tracked.
- `.env.example` contains only empty JWT/AI key placeholders and safe configuration examples.
- No real JWT secret, API key, password, or JWT was added to source, tests, logs committed to Git, or this report.
- Tests use a clearly scoped fixed integration-test JWT secret only inside test source.
- Acceptance used a randomly generated process-only JWT secret.

## 16. M1–M6.5 regression result

- Backend default suite: PASS.
- M6 authenticated real SSE infrastructure chain: PASS.
- Flyway V1–V5 validation/migration: PASS on MySQL 8.4.
- MySQL, Redis, MinIO, RocketMQ NameServer, and RocketMQ Broker: all healthy.
- Upload/MinIO/MySQL: PASS.
- RocketMQ producer/consumer and idempotency: PASS.
- Redis progress and MySQL fallback: PASS.
- FFmpeg and temporary media cleanup: PASS.
- Mock ASR and structured Mock Summary: PASS.
- DashScope/Groq/DeepSeek provider contract tests: PASS without network calls.
- Transcript, Summary, Chapters, Key Points APIs: PASS with ownership.
- SSE and GET polling fallback: PASS with JWT.
- Frontend type-check and production build: PASS.
- Automated tests did not call paid AI APIs. Separately, the user manually confirmed the real DashScope + DeepSeek browser chain with correctly configured local environment variables.

## Commands executed

Representative commands actually executed during acceptance:

```powershell
git status --short
git log --oneline -5
docker compose ps

mvn -q --settings "D:\Vibe Coding\Video agent\backend\.mvn\settings.xml" `
  "-Dmaven.repo.local=D:\Vibe Coding\Video agent\tmp\codex-m66-backend-0f2047c756a74fb087c47504c24e900e\.m2-repository" `
  "-DargLine=-Djava.io.tmpdir=junit-tmp -Djdk.net.URLClassPath.disableClassPathURLCheck=true" test

$env:VIDEOAGENT_M6_INFRA_TEST='true'
mvn ... "-Dtest=AnalysisSseInfrastructureIntegrationTest" test

$env:VIDEOAGENT_M66_INFRA_TEST='true'
mvn ... "-Dtest=Milestone66InfrastructureIntegrationTest,SecurityEndpointIntegrationTest" test

.\node_modules\.bin\vue-tsc.cmd --noEmit -p tsconfig.app.json --incremental false
.\node_modules\.bin\tsc.cmd --noEmit -p tsconfig.node.json --incremental false
.\node_modules\.bin\vite.cmd build --configLoader runner --outDir ..\tmp\codex-m66-frontend-dist --emptyOutDir

powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Vibe Coding\Video agent\tmp\m66_api_acceptance.ps1"
```

The temporary API acceptance script was deleted after successful execution.

## Scope decision

M6.6 implementation and acceptance are complete. The independent `feat: complete milestone 6.6 authentication and video management` checkpoint is ready to be created. No M7/RAG work has started.
