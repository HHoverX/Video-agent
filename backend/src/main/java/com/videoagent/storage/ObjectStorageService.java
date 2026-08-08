package com.videoagent.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    void putObject(String objectKey, InputStream inputStream, long size, String contentType);

    void removeObject(String objectKey);
}
