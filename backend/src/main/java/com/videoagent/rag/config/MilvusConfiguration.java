package com.videoagent.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class MilvusConfiguration {

    @Bean
    @Lazy
    public MilvusClientV2 milvusClient(MilvusProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
            .uri(properties.uri())
            .connectTimeoutMs(properties.timeout().toMillis())
            .rpcDeadlineMs(properties.timeout().toMillis());
        if (!properties.token().isBlank()) {
            builder.token(properties.token());
        }
        return new MilvusClientV2(builder.build());
    }
}
