package com.videoagent.storage;

import io.minio.MinioClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

@Configuration
public class StorageConfiguration {

    @Bean
    @Lazy
    @Primary
    public MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
            .endpoint(properties.endpoint())
            .credentials(properties.accessKey(), properties.secretKey())
            .build();
    }

    @Bean
    @Lazy
    public MinioClient publicPresignMinioClient(StorageProperties properties) {
        return MinioClient.builder()
            .endpoint(properties.publicEndpoint())
            .credentials(properties.accessKey(), properties.secretKey())
            .build();
    }
}
