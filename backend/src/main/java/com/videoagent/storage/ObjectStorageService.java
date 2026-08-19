package com.videoagent.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ObjectStorageService {

    void putObject(String objectKey, InputStream inputStream, long size, String contentType);

    void downloadObject(String objectKey, Path destination);

    void removeObject(String objectKey);

    String presignPutObject(String objectKey, Duration expiry);

    StoredObject statObject(String objectKey);

    byte[] readObjectRange(String objectKey, long offset, int length);

    void composeObject(String objectKey, List<ComposeObjectSource> sources, String contentType);

    String sha256Object(String objectKey);
}
