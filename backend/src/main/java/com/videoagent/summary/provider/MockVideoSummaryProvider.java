package com.videoagent.summary.provider;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.summary.service.SummaryResultValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MockVideoSummaryProvider implements VideoSummaryProvider {

    private static final int MAX_MOCK_KEY_POINTS = 5;

    private final SummaryResultValidator validator;

    public MockVideoSummaryProvider(SummaryResultValidator validator) {
        this.validator = validator;
    }

    @Override
    public VideoSummaryResult summarize(VideoSummaryRequest request) {
        List<TranscriptSegment> segments = request.transcriptSegments().stream()
            .sorted(Comparator.comparingLong(TranscriptSegment::startMs))
            .toList();
        String overview = "视频主要内容：" + joinText(segments);

        List<SummaryChapter> chapters = new ArrayList<>();
        int split = Math.max(1, (segments.size() + 1) / 2);
        chapters.add(chapter("内容概览", segments.subList(0, split)));
        if (split < segments.size()) {
            chapters.add(chapter("后续内容", segments.subList(split, segments.size())));
        }

        List<SummaryKeyPoint> keyPoints = segments.stream()
            .limit(MAX_MOCK_KEY_POINTS)
            .map(segment -> new SummaryKeyPoint(
                segment.text(),
                segment.startMs(),
                segment.endMs()
            ))
            .toList();

        return validator.validate(
            request,
            new VideoSummaryResult(overview, chapters, keyPoints)
        );
    }

    private SummaryChapter chapter(String title, List<TranscriptSegment> segments) {
        TranscriptSegment first = segments.getFirst();
        TranscriptSegment last = segments.getLast();
        return new SummaryChapter(
            title,
            joinText(segments),
            first.startMs(),
            last.endMs()
        );
    }

    private String joinText(List<TranscriptSegment> segments) {
        return segments.stream()
            .map(TranscriptSegment::text)
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }
}
