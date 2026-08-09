package com.videoagent.summary.service;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class SummaryResultValidator {

    private static final int MAX_OVERVIEW_LENGTH = 20_000;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_ITEM_TEXT_LENGTH = 2_000;
    private static final int MAX_CHAPTERS = 50;
    private static final int MAX_KEY_POINTS = 100;

    public VideoSummaryResult validate(VideoSummaryRequest request, VideoSummaryResult result) {
        if (request.transcriptSegments().isEmpty()) {
            throw invalid("总结请求缺少 transcript segments");
        }
        if (result == null) {
            throw invalid("LLM 未返回结构化总结");
        }

        long transcriptStart = request.transcriptSegments().stream()
            .mapToLong(TranscriptSegment::startMs)
            .min()
            .orElseThrow();
        long transcriptEnd = request.transcriptSegments().stream()
            .mapToLong(TranscriptSegment::endMs)
            .max()
            .orElseThrow();

        String overview = requiredText(result.overview(), MAX_OVERVIEW_LENGTH, "overview");
        if (result.chapters().isEmpty() || result.chapters().size() > MAX_CHAPTERS) {
            throw invalid("chapters 数量必须在 1 到 " + MAX_CHAPTERS + " 之间");
        }
        if (result.keyPoints().isEmpty() || result.keyPoints().size() > MAX_KEY_POINTS) {
            throw invalid("keyPoints 数量必须在 1 到 " + MAX_KEY_POINTS + " 之间");
        }

        List<SummaryChapter> chapters = result.chapters().stream()
            .map(chapter -> validateChapter(chapter, transcriptStart, transcriptEnd))
            .sorted(Comparator.comparingLong(SummaryChapter::startMs)
                .thenComparingLong(SummaryChapter::endMs))
            .toList();
        List<SummaryKeyPoint> keyPoints = result.keyPoints().stream()
            .map(point -> validateKeyPoint(point, transcriptStart, transcriptEnd))
            .sorted(Comparator.comparingLong(SummaryKeyPoint::startMs)
                .thenComparingLong(SummaryKeyPoint::endMs))
            .toList();

        return new VideoSummaryResult(overview, chapters, keyPoints);
    }

    private SummaryChapter validateChapter(
        SummaryChapter chapter,
        long transcriptStart,
        long transcriptEnd
    ) {
        if (chapter == null) {
            throw invalid("chapter 不能为空");
        }
        validateRange(chapter.startMs(), chapter.endMs(), transcriptStart, transcriptEnd, "chapter");
        return new SummaryChapter(
            requiredText(chapter.title(), MAX_TITLE_LENGTH, "chapter.title"),
            requiredText(chapter.summary(), MAX_ITEM_TEXT_LENGTH, "chapter.summary"),
            chapter.startMs(),
            chapter.endMs()
        );
    }

    private SummaryKeyPoint validateKeyPoint(
        SummaryKeyPoint point,
        long transcriptStart,
        long transcriptEnd
    ) {
        if (point == null) {
            throw invalid("keyPoint 不能为空");
        }
        validateRange(point.startMs(), point.endMs(), transcriptStart, transcriptEnd, "keyPoint");
        return new SummaryKeyPoint(
            requiredText(point.content(), MAX_ITEM_TEXT_LENGTH, "keyPoint.content"),
            point.startMs(),
            point.endMs()
        );
    }

    private void validateRange(
        long startMs,
        long endMs,
        long transcriptStart,
        long transcriptEnd,
        String field
    ) {
        if (startMs < transcriptStart || endMs > transcriptEnd || endMs <= startMs) {
            throw invalid(field + " 时间范围超出 transcript 边界");
        }
    }

    private String requiredText(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw invalid(field + " 为空或超过长度限制");
        }
        return normalized;
    }

    private VideoAgentException invalid(String message) {
        return new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID, message);
    }
}
