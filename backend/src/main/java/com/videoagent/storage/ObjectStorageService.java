package com.videoagent.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorageService {

    void putObject(String objectKey, InputStream inputStream, long size, String contentType);

    void downloadObject(String objectKey, Path destination);

    void removeObject(String objectKey);
}
