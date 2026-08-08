package com.videoagent.video.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "videoagent.upload")
public record VideoUploadProperties(DataSize maxFileSize) {

    public VideoUploadProperties {
        if (maxFileSize == null) {
            maxFileSize = DataSize.ofMegabytes(500);
        }
    }
}
