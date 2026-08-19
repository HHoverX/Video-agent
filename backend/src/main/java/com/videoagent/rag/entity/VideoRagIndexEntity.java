package com.videoagent.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("video_rag_index")
public class VideoRagIndexEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long analysisTaskId;
    private String status;
    private String contextMode;
    private Integer transcriptChars;
    private Integer chunkCount;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String buildToken;
    private LocalDateTime buildStartedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public Long getAnalysisTaskId() {
        return analysisTaskId;
    }

    public void setAnalysisTaskId(Long analysisTaskId) {
        this.analysisTaskId = analysisTaskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContextMode() {
        return contextMode;
    }

    public void setContextMode(String contextMode) {
        this.contextMode = contextMode;
    }

    public Integer getTranscriptChars() {
        return transcriptChars;
    }

    public void setTranscriptChars(Integer transcriptChars) {
        this.transcriptChars = transcriptChars;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public String getBuildToken() { return buildToken; }

    public void setBuildToken(String buildToken) { this.buildToken = buildToken; }

    public LocalDateTime getBuildStartedAt() { return buildStartedAt; }

    public void setBuildStartedAt(LocalDateTime buildStartedAt) { this.buildStartedAt = buildStartedAt; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
