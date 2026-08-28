package com.videoagent.summary.provider;

import com.videoagent.asr.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;

public class MockVideoSummaryProvider implements VideoSummaryProvider {

    private static final int MAX_MOCK_KEY_POINTS = 5;

    @Override
    public VideoSummaryDraft summarize(VideoSummaryRequest request) {
        String overview = "视频主要内容：" + joinText(request);

        List<VideoSummaryDraft.Chapter> chapters = new ArrayList<>();
        int split = Math.max(1, (request.transcriptSegments().size() + 1) / 2);
        chapters.add(chapter("内容概览", 0, split - 1, request));
        if (split < request.transcriptSegments().size()) {
            chapters.add(chapter("后续内容", split, request.transcriptSegments().size() - 1, request));
        }

        List<VideoSummaryDraft.KeyPoint> keyPoints = java.util.stream.IntStream
            .range(0, request.transcriptSegments().size())
            .limit(MAX_MOCK_KEY_POINTS)
            .mapToObj(index -> new VideoSummaryDraft.KeyPoint(
                request.transcriptSegments().get(index).text(), evidenceId(index), evidenceId(index)))
            .toList();

        return new VideoSummaryDraft(overview, chapters, keyPoints);
    }

    private VideoSummaryDraft.Chapter chapter(
        String title,
        int startIndex,
        int endIndex,
        VideoSummaryRequest request
    ) {
        return new VideoSummaryDraft.Chapter(
            title,
            joinText(request.transcriptSegments().subList(startIndex, endIndex + 1)),
            evidenceId(startIndex),
            evidenceId(endIndex)
        );
    }

    private String joinText(VideoSummaryRequest request) {
        return joinText(request.transcriptSegments());
    }

    private String joinText(List<TranscriptSegment> segments) {
        return segments.stream()
            .map(segment -> segment.text())
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }

    private String evidenceId(int index) {
        return "E" + index;
    }
}
