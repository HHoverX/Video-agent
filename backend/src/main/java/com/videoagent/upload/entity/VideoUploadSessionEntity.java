package com.videoagent.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("video_upload_session")
public class VideoUploadSessionEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String fileName;
    private String title;
    private Long fileSize;
    private String contentType;
    private Long chunkSize;
    private Integer totalParts;
    private String objectKey;
    private String tempPrefix;
    private String expectedSha256;
    private String status;
    private String lastError;
    private LocalDateTime expiresAt;
    private Long videoId;
    private Long analysisTaskId;
    private LocalDateTime tempCleanedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getChunkSize() { return chunkSize; }
    public void setChunkSize(Long chunkSize) { this.chunkSize = chunkSize; }
    public Integer getTotalParts() { return totalParts; }
    public void setTotalParts(Integer totalParts) { this.totalParts = totalParts; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getTempPrefix() { return tempPrefix; }
    public void setTempPrefix(String tempPrefix) { this.tempPrefix = tempPrefix; }
    public String getExpectedSha256() { return expectedSha256; }
    public void setExpectedSha256(String expectedSha256) { this.expectedSha256 = expectedSha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public Long getAnalysisTaskId() { return analysisTaskId; }
    public void setAnalysisTaskId(Long analysisTaskId) { this.analysisTaskId = analysisTaskId; }
    public LocalDateTime getTempCleanedAt() { return tempCleanedAt; }
    public void setTempCleanedAt(LocalDateTime tempCleanedAt) { this.tempCleanedAt = tempCleanedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
