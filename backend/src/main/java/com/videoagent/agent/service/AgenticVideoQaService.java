package com.videoagent.agent.service;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.dto.AgenticCitation;
import com.videoagent.agent.dto.AgenticQaResponse;
import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.agent.evidence.EvidenceNormalizer;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.agent.plan.RetrievalPlanValidator;
import com.videoagent.agent.plan.RetrievalStrategy;
import com.videoagent.agent.planner.RetrievalPlannerProvider;
import com.videoagent.agent.qa.AgenticAnswerProvider;
import com.videoagent.agent.qa.AgenticQaResult;
import com.videoagent.agent.tool.AgenticToolExecutor;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.ContextStrategyResolver;
import com.videoagent.rag.context.QaContextMode;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.rag.service.VideoQaService;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agentic video QA: plan -> validate -> execute tools -> normalize evidence ->
 * synthesize -> validate citations. Ownership is enforced at the boundary and
 * every tool runs inside the server-bound context, so the LLM can never select
 * a user or video. Only transient planner failures and invalid planner output
 * fall back to M8.1 Basic QA; provider authentication/configuration failures
 * are propagated instead of being silently hidden.
 */
@Service
public class AgenticVideoQaService {

    private static final Logger log = LoggerFactory.getLogger(AgenticVideoQaService.class);

    private final VideoOwnershipService ownershipService;
    private final VideoTranscriptSegmentRepository segmentRepository;
    private final VideoSummaryService summaryService;
    private final RagIndexService ragIndexService;
    private final ContextStrategyResolver strategyResolver;
    private final RetrievalPlannerProvider planner;
    private final RetrievalPlanValidator planValidator;
    private final AgenticToolExecutor toolExecutor;
    private final EvidenceNormalizer evidenceNormalizer;
    private final AgenticAnswerProvider answerProvider;
    private final VideoQaService basicQaService;

    public AgenticVideoQaService(
        VideoOwnershipService ownershipService,
        VideoTranscriptSegmentRepository segmentRepository,
        VideoSummaryService summaryService,
        RagIndexService ragIndexService,
        ContextStrategyResolver strategyResolver,
        RetrievalPlannerProvider planner,
        RetrievalPlanValidator planValidator,
        AgenticToolExecutor toolExecutor,
        EvidenceNormalizer evidenceNormalizer,
        AgenticAnswerProvider answerProvider,
        VideoQaService basicQaService
    ) {
        this.ownershipService = ownershipService;
        this.segmentRepository = segmentRepository;
        this.summaryService = summaryService;
        this.ragIndexService = ragIndexService;
        this.strategyResolver = strategyResolver;
        this.planner = planner;
        this.planValidator = planValidator;
        this.toolExecutor = toolExecutor;
        this.evidenceNormalizer = evidenceNormalizer;
        this.answerProvider = answerProvider;
        this.basicQaService = basicQaService;
    }

    public AgenticQaResponse answerAgentic(long videoId, long userId, String question) {
        long startedAtNanos = System.nanoTime();
        QaTelemetryContext telemetryContext = QaTelemetryContext.newRequest(videoId);
        boolean completionDelegatedToBasic = false;
        int toolActionCount = 0;
        int evidenceCount = 0;
        String outcome = "failure";
        String errorCategory = ErrorCode.INTERNAL_ERROR.name();
        try {
            ownershipService.requireOwned(videoId, userId);

            List<VideoTranscriptSegmentEntity> segments = segmentRepository.findLatestSuccessfulByVideoId(videoId);
            AgenticQaContext context = buildContext(videoId, userId, segments);
            telemetryContext = telemetryContext.withAnalysisTaskId(context.analysisTaskId());
            if (segments.isEmpty()) {
                completionDelegatedToBasic = true;
                return fallbackToBasic(videoId, userId, question, telemetryContext, context);
            }

            RetrievalPlan plan;
            List<RetrievalAction> actions;
            RetrievalStrategy strategy;
            try {
                plan = planner.plan(context, question, telemetryContext);
                planValidator.validate(plan, context);
                actions = plan.actions().stream().distinct().toList();
                strategy = RetrievalStrategy.derive(actions);
            } catch (RuntimeException plannerFailure) {
                if (!isFallbackEligible(plannerFailure)) {
                    throw plannerFailure;
                }
                ErrorCode errorCode = plannerFailure instanceof VideoAgentException exception
                    ? exception.errorCode()
                    : ErrorCode.INTERNAL_ERROR;
                log.warn("[requestId={}][userId={}][videoId={}][analysisTaskId={}][errorCode={}][exceptionClass={}] agentic planner failed; using BASIC_FALLBACK",
                    telemetryContext.requestId(), userId, videoId, telemetryContext.analysisTaskId(), errorCode,
                    plannerFailure.getClass().getSimpleName());
                completionDelegatedToBasic = true;
                return fallbackToBasic(videoId, userId, question, telemetryContext, context);
            }

            List<String> toolsUsed = actions.stream()
                .filter(a -> a != null && a.tool() != null)
                .map(a -> a.tool().name())
                .toList();
            toolActionCount = toolsUsed.size();

            List<EvidenceItem> rawEvidence = toolExecutor.execute(context, actions, telemetryContext);
            List<EvidenceItem> evidence = evidenceNormalizer.dedupeAndLimit(rawEvidence);
            evidenceCount = evidence.size();

            if (evidence.isEmpty()) {
                log.info("[requestId={}][userId={}][videoId={}][strategy={}][toolCount={}] no evidence; answering cannot-determine",
                    telemetryContext.requestId(), userId, videoId, strategy, toolsUsed.size());
                outcome = "success";
                errorCategory = "none";
                return new AgenticQaResponse(
                    "根据当前视频内容无法确定。",
                    strategy.name(),
                    context.contextMode() == null ? null : context.contextMode().name(),
                    toolsUsed,
                    List.of()
                );
            }

            AgenticQaResult result = answerProvider.synthesize(question, evidence, telemetryContext, toolActionCount);
            Map<String, EvidenceItem> byId = new LinkedHashMap<>();
            for (EvidenceItem item : evidence) {
                byId.put(item.evidenceId(), item);
            }
            List<AgenticCitation> citations = resolveCitations(result.citationEvidenceIds(), byId);
            if (citations.isEmpty()) {
                outcome = "success";
                errorCategory = "none";
                return new AgenticQaResponse(
                    "根据当前视频内容无法确定。",
                    strategy.name(),
                    context.contextMode() == null ? null : context.contextMode().name(),
                    toolsUsed,
                    List.of()
                );
            }

            log.info("[requestId={}][userId={}][videoId={}][strategy={}][contextMode={}][toolCount={}][toolsUsed={}] agentic qa answered",
                telemetryContext.requestId(), userId, videoId, strategy,
                context.contextMode() == null ? null : context.contextMode().name(),
                toolsUsed.size(), toolsUsed);
            outcome = "success";
            errorCategory = "none";
            return new AgenticQaResponse(
                result.answer(),
                strategy.name(),
                context.contextMode() == null ? null : context.contextMode().name(),
                toolsUsed,
                citations
            );
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } finally {
            if (!completionDelegatedToBasic) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                log.info("event=ai.qa_request requestId={} videoId={} analysisTaskId={} route={} totalDurationMs={} outcome={} errorCategory={} fallback={} toolActionCount={} evidenceCount={}",
                    telemetryContext.requestId(), telemetryContext.videoId(), telemetryContext.analysisTaskId(),
                    QaTelemetryRoute.AGENTIC.value(), durationMs, outcome, errorCategory, false,
                    toolActionCount, evidenceCount);
            }
        }
    }

    private AgenticQaContext buildContext(
        long videoId,
        long userId,
        List<VideoTranscriptSegmentEntity> segments
    ) {
        QaContextMode mode = segments.isEmpty()
            ? null
            : strategyResolver.resolveMode(segments);

        Long taskId = segments.isEmpty() ? null : segments.getFirst().getTaskId();
        boolean hasSummary = summaryService.getSummary(videoId, userId).isPresent();
        VideoRagIndexEntity index = ragIndexService.getStatus(videoId, userId, segments);
        String ragStatus = index == null ? null : index.getStatus();

        return new AgenticQaContext(
            userId,
            videoId,
            taskId,
            mode,
            !segments.isEmpty(),
            hasSummary,
            ragStatus
        );
    }

    private AgenticQaResponse fallbackToBasic(
        long videoId,
        long userId,
        String question,
        QaTelemetryContext telemetryContext,
        AgenticQaContext context
    ) {
        QaResponse basic = basicQaService.answerWithContext(
            videoId,
            userId,
            question,
            telemetryContext,
            QaTelemetryRoute.AGENTIC_FALLBACK_BASIC
        );
        List<AgenticCitation> citations = basic.citations() == null
            ? List.of()
            : basic.citations().stream()
                .map(c -> new AgenticCitation("TRANSCRIPT_SEARCH", c.startMs(), c.endMs(), c.text()))
                .toList();
        log.info("[requestId={}][userId={}][videoId={}][strategy=BASIC_FALLBACK][contextMode={}] agentic qa fell back to basic qa",
            telemetryContext.requestId(), userId, videoId,
            context.contextMode() == null ? null : context.contextMode().name());
        return new AgenticQaResponse(
            basic.answer(),
            "BASIC_FALLBACK",
            basic.mode(),
            List.of(),
            citations
        );
    }

    private List<AgenticCitation> resolveCitations(
        List<String> evidenceIds,
        Map<String, EvidenceItem> byId
    ) {
        Set<String> dedup = new LinkedHashSet<>();
        List<AgenticCitation> citations = new ArrayList<>();
        if (evidenceIds == null) {
            return citations;
        }
        for (String id : evidenceIds) {
            if (id == null || !dedup.add(id)) {
                continue;
            }
            EvidenceItem item = byId.get(id);
            if (item == null) {
                continue;
            }
            citations.add(new AgenticCitation(
                item.sourceType().name(),
                item.startMs(),
                item.endMs(),
                item.text()
            ));
        }
        return citations;
    }

    private boolean isFallbackEligible(RuntimeException failure) {
        if (!(failure instanceof VideoAgentException exception)) {
            return false;
        }
        return exception.errorCode() == ErrorCode.AGENT_PLANNER_FAILED
            || exception.errorCode() == ErrorCode.INVALID_REQUEST;
    }
}
