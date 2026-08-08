package com.videoagent.storage;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MinioStorageService implements ObjectStorageService {

    private final ObjectProvider<MinioClient> clientProvider;
    private final StorageProperties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioStorageService(
        ObjectProvider<MinioClient> clientProvider,
        StorageProperties properties
    ) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    @Override
    public void downloadObject(String objectKey, Path destination) {
        try (InputStream inputStream = clientProvider.getObject().getObject(GetObjectArgs.builder()
            .bucket(properties.bucket())
            .object(objectKey)
            .build())) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new VideoAgentException(
                ErrorCode.STORAGE_ERROR,
                "视频从对象存储下载失败",
                exception
            );
        }
    }

    @Override
    public void putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            MinioClient client = clientProvider.getObject();
            ensureBucket(client);
            client.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .stream(inputStream, size, -1)
                .contentType(contentType)
                .build());
        } catch (Exception exception) {
            throw new VideoAgentException(
                ErrorCode.STORAGE_ERROR,
                "视频写入对象存储失败",
                exception
            );
        }
    }

    @Override
    public void removeObject(String objectKey) {
        try {
            clientProvider.getObject().removeObject(RemoveObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
        } catch (Exception exception) {
            throw new VideoAgentException(
                ErrorCode.STORAGE_ERROR,
                "对象存储补偿删除失败",
                exception
            );
        }
    }

    private void ensureBucket(MinioClient client) throws Exception {
        if (bucketReady.get()) {
            return;
        }

        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }

            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.bucket())
                    .build());
            }
            bucketReady.set(true);
        }
    }
}
