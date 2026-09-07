package com.videoagent.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("video_rag_chunk")
public class VideoRagChunkEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String chunkId;
    private Long userId;
    private Long videoId;
    private Long analysisTaskId;
    private Integer chunkIndex;
    private String text;
    private Long startMs;
    private Long endMs;
    private String sourceSegmentIndexes;
    private LocalDateTime createdAt;
    @TableField(exist = false)
    private Double lexicalScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public Long getAnalysisTaskId() { return analysisTaskId; }
    public void setAnalysisTaskId(Long analysisTaskId) { this.analysisTaskId = analysisTaskId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Long getStartMs() { return startMs; }
    public void setStartMs(Long startMs) { this.startMs = startMs; }
    public Long getEndMs() { return endMs; }
    public void setEndMs(Long endMs) { this.endMs = endMs; }
    public String getSourceSegmentIndexes() { return sourceSegmentIndexes; }
    public void setSourceSegmentIndexes(String sourceSegmentIndexes) { this.sourceSegmentIndexes = sourceSegmentIndexes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Double getLexicalScore() { return lexicalScore; }
    public void setLexicalScore(Double lexicalScore) { this.lexicalScore = lexicalScore; }
}
