package com.videoagent.storage;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
                .headers(Map.of("Content-Type", contentType))
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

    @Override
    public String presignPutObject(String objectKey, Duration expiry) {
        try {
            MinioClient client = clientProvider.getObject();
            ensureBucket(client);
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(properties.bucket())
                .object(objectKey)
                .expiry(Math.toIntExact(expiry.toSeconds()))
                .build());
        } catch (Exception exception) {
            throw storageFailure("无法生成分片上传地址", exception);
        }
    }

    @Override
    public StoredObject statObject(String objectKey) {
        try {
            StatObjectResponse response = clientProvider.getObject().statObject(StatObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
            return new StoredObject(objectKey, response.size(), response.etag(), response.contentType());
        } catch (Exception exception) {
            throw storageFailure("无法读取对象信息", exception);
        }
    }

    @Override
    public byte[] readObjectRange(String objectKey, long offset, int length) {
        try (InputStream inputStream = clientProvider.getObject().getObject(GetObjectArgs.builder()
            .bucket(properties.bucket())
            .object(objectKey)
            .offset(offset)
            .length((long) length)
            .build())) {
            return inputStream.readNBytes(length);
        } catch (Exception exception) {
            throw storageFailure("无法读取对象内容", exception);
        }
    }

    @Override
    public void composeObject(String objectKey, List<ComposeObjectSource> sourceObjects, String contentType) {
        if (sourceObjects == null || sourceObjects.isEmpty()) {
            throw new VideoAgentException(ErrorCode.STORAGE_ERROR, "没有可合并的分片对象");
        }
        try {
            MinioClient client = clientProvider.getObject();
            ensureBucket(client);
            List<ComposeSource> sources = sourceObjects.stream()
                .map(source -> ComposeSource.builder()
                    .bucket(properties.bucket())
                    .object(source.objectKey())
                    .matchETag(source.etag())
                    .build())
                .toList();
            client.composeObject(ComposeObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .headers(Map.of("Content-Type", contentType))
                .sources(sources)
                .build());
        } catch (Exception exception) {
            throw storageFailure("视频分片合并失败", exception);
        }
    }

    @Override
    public String sha256Object(String objectKey) {
        try (InputStream inputStream = clientProvider.getObject().getObject(GetObjectArgs.builder()
            .bucket(properties.bucket())
            .object(objectKey)
            .build())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "运行环境不支持 SHA-256", exception);
        } catch (Exception exception) {
            throw storageFailure("无法校验合并文件摘要", exception);
        }
    }

    private VideoAgentException storageFailure(String message, Exception exception) {
        return new VideoAgentException(ErrorCode.STORAGE_ERROR, message, exception);
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
