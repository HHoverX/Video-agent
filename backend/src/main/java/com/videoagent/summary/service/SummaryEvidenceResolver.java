package com.videoagent.summary.service;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryDraft;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SummaryEvidenceResolver {

    private static final Pattern EVIDENCE_ID = Pattern.compile("^E(0|[1-9][0-9]*)$");

    public VideoSummaryResult resolve(VideoSummaryRequest request, VideoSummaryDraft draft) {
        if (request == null || request.transcriptSegments().isEmpty()) {
            throw invalid("总结请求缺少 transcript segments");
        }
        if (draft == null) {
            throw invalid("LLM 未返回结构化总结");
        }

        List<TranscriptSegment> segments = request.transcriptSegments();
        validateTranscriptOrder(segments);
        return new VideoSummaryResult(
            draft.overview(),
            draft.chapters().stream().map(chapter -> resolveChapter(chapter, segments)).toList(),
            draft.keyPoints().stream().map(point -> resolveKeyPoint(point, segments)).toList()
        );
    }

    private SummaryChapter resolveChapter(
        VideoSummaryDraft.Chapter chapter,
        List<TranscriptSegment> segments
    ) {
        if (chapter == null) {
            throw invalid("chapter 不能为空");
        }
        Range range = resolveRange(chapter.startEvidenceId(), chapter.endEvidenceId(), segments, "chapter");
        return new SummaryChapter(chapter.title(), chapter.summary(), range.startMs(), range.endMs());
    }

    private SummaryKeyPoint resolveKeyPoint(
        VideoSummaryDraft.KeyPoint point,
        List<TranscriptSegment> segments
    ) {
        if (point == null) {
            throw invalid("keyPoint 不能为空");
        }
        Range range = resolveRange(point.startEvidenceId(), point.endEvidenceId(), segments, "keyPoint");
        return new SummaryKeyPoint(point.content(), range.startMs(), range.endMs());
    }

    private Range resolveRange(
        String startEvidenceId,
        String endEvidenceId,
        List<TranscriptSegment> segments,
        String field
    ) {
        int startIndex = parseEvidenceIndex(startEvidenceId, field + ".startEvidenceId");
        int endIndex = parseEvidenceIndex(endEvidenceId, field + ".endEvidenceId");
        if (startIndex >= segments.size() || endIndex >= segments.size()) {
            throw invalid(field + " 引用了不存在的 evidence ID");
        }
        if (startIndex > endIndex) {
            throw invalid(field + " evidence range 反向");
        }
        TranscriptSegment start = segments.get(startIndex);
        TranscriptSegment end = segments.get(endIndex);
        return new Range(start.startMs(), end.endMs());
    }

    private int parseEvidenceIndex(String evidenceId, String field) {
        Matcher matcher = EVIDENCE_ID.matcher(evidenceId == null ? "" : evidenceId);
        if (!matcher.matches()) {
            throw invalid(field + " 格式不合法");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid(field + " 超出支持范围");
        }
    }

    private void validateTranscriptOrder(List<TranscriptSegment> segments) {
        long previousStartMs = -1;
        long previousEndMs = -1;
        for (TranscriptSegment segment : segments) {
            if (segment == null || segment.startMs() < previousStartMs || segment.endMs() < previousEndMs) {
                throw invalid("transcript evidence 顺序不合法");
            }
            previousStartMs = segment.startMs();
            previousEndMs = segment.endMs();
        }
    }

    private VideoAgentException invalid(String message) {
        return new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID, message);
    }

    private record Range(long startMs, long endMs) {
    }
}
