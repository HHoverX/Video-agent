package com.videoagent.storage;

public record StoredObject(
    String objectKey,
    long size,
    String etag,
    String contentType
) {
}
