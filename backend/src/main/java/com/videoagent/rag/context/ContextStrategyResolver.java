package com.videoagent.rag.context;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rule-based adaptive context strategy. Decides whether to answer a question
 * with the full transcript (DIRECT_CONTEXT) or with RAG retrieval, based on the
 * transcript's total character count — never on video duration. A short,
 * low-speech-density video can have a tiny transcript; a dense short course can
 * have a huge one. Transcript size is the property that actually determines how
 * much context is practical to send to the LLM.
 */
@Component
public class ContextStrategyResolver {

    private final RagProperties properties;

    public ContextStrategyResolver(RagProperties properties) {
        this.properties = properties;
    }

    public QaContextMode resolveMode(List<VideoTranscriptSegmentEntity> segments) {
        long chars = transcriptChars(segments);
        if (chars <= properties.directContextMaxChars()) {
            return QaContextMode.DIRECT_CONTEXT;
        }
        return QaContextMode.RAG;
    }

    public long transcriptChars(List<VideoTranscriptSegmentEntity> segments) {
        long total = 0;
        for (VideoTranscriptSegmentEntity segment : segments) {
            if (segment.getText() != null) {
                total += segment.getText().length();
            }
        }
        return total;
    }

    public List<VideoTranscriptSegmentEntity> requireNonEmpty(List<VideoTranscriptSegmentEntity> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED, "该视频暂无可用字幕，无法进行问答");
        }
        return segments;
    }
}
