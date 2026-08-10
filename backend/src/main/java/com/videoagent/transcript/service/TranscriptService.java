package com.videoagent.transcript.service;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TranscriptService {

    private final VideoTranscriptSegmentRepository segmentRepository;
    private final VideoOwnershipService ownershipService;

    public TranscriptService(
        VideoTranscriptSegmentRepository segmentRepository,
        VideoOwnershipService ownershipService
    ) {
        this.segmentRepository = segmentRepository;
        this.ownershipService = ownershipService;
    }

    @Transactional
    public void replaceTaskSegments(AnalysisTaskEntity task, TranscriptionResult result) {
        if (result.segments().isEmpty()) {
            throw new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED, "ASR 未返回字幕片段");
        }

        segmentRepository.deleteByTaskId(task.getId());
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < result.segments().size(); index++) {
            TranscriptSegment segment = result.segments().get(index);
            VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
            entity.setVideoId(task.getVideoId());
            entity.setTaskId(task.getId());
            entity.setSegmentIndex(index);
            entity.setStartMs(segment.startMs());
            entity.setEndMs(segment.endMs());
            entity.setText(segment.text());
            entity.setCreatedAt(now);
            if (segmentRepository.insert(entity) != 1) {
                throw new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED, "字幕片段保存失败");
            }
        }
    }

    /**
     * Durable resume basis: whether transcript rows already exist for this task
     * in MySQL. Used to decide whether the ASR/FFmpeg/MinIO pipeline must run
     * again or can be skipped entirely.
     */
    public boolean taskHasPersistedSegments(long taskId) {
        return segmentRepository.countByTaskId(taskId) > 0;
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegment> loadTaskSegments(long taskId) {
        return segmentRepository.findByTaskId(taskId).stream()
            .map(entity -> new TranscriptSegment(
                entity.getStartMs(),
                entity.getEndMs(),
                entity.getText()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegmentResponse> getVideoTranscript(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return segmentRepository.findLatestSuccessfulByVideoId(videoId)
            .stream()
            .map(TranscriptSegmentResponse::from)
            .toList();
    }
}
