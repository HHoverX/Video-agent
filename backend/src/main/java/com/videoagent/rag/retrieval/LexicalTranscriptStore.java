package com.videoagent.rag.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.rag.chunk.TranscriptChunk;
import com.videoagent.rag.entity.VideoRagChunkEntity;
import com.videoagent.rag.repository.VideoRagChunkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class LexicalTranscriptStore {
    private static final TypeReference<List<Integer>> INDEXES = new TypeReference<>() { };

    private final VideoRagChunkRepository repository;
    private final ObjectMapper objectMapper;

    public LexicalTranscriptStore(VideoRagChunkRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void replace(long userId, long videoId, long taskId, List<TranscriptChunk> chunks) {
        repository.deleteByUserAndVideo(userId, videoId);
        LocalDateTime now = LocalDateTime.now();
        for (TranscriptChunk chunk : chunks) {
            VideoRagChunkEntity entity = new VideoRagChunkEntity();
            entity.setChunkId(ChunkIdentity.of(videoId, taskId, chunk.chunkIndex()));
            entity.setUserId(userId);
            entity.setVideoId(videoId);
            entity.setAnalysisTaskId(taskId);
            entity.setChunkIndex(chunk.chunkIndex());
            entity.setText(chunk.text());
            entity.setStartMs(chunk.startMs());
            entity.setEndMs(chunk.endMs());
            entity.setSourceSegmentIndexes(writeIndexes(chunk.sourceSegmentIndexes()));
            entity.setCreatedAt(now);
            repository.insert(entity);
        }
    }

    @Transactional(readOnly = true)
    public List<LexicalChunk> search(long userId, long videoId, String query, int limit) {
        return repository.search(userId, videoId, query, limit).stream()
            .map(row -> new LexicalChunk(
                row.getChunkId(), row.getChunkIndex(), row.getText(), row.getStartMs(), row.getEndMs(),
                readIndexes(row.getSourceSegmentIndexes()),
                row.getLexicalScore() == null ? 0.0 : row.getLexicalScore()
            ))
            .toList();
    }

    private String writeIndexes(List<Integer> indexes) {
        try {
            return objectMapper.writeValueAsString(indexes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize transcript segment indexes", exception);
        }
    }

    private List<Integer> readIndexes(String json) {
        try {
            return objectMapper.readValue(json, INDEXES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse transcript segment indexes", exception);
        }
    }
}
