package com.videoagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class UploadGatewayConfigurationTest {

    @Test
    void shouldApplyHardLimitsToPutBeforeStreamingToMinio() throws Exception {
        Path template = Path.of(System.getProperty("user.dir"))
            .resolve("../infra/nginx/upload-gateway.conf.template")
            .normalize();
        String nginx = Files.readString(template);

        assertThat(nginx)
            .contains("PUT $binary_remote_addr")
            .contains("limit_conn upload_per_ip ${UPLOAD_MAX_CONNECTIONS_PER_IP}")
            .contains("limit_conn upload_global ${UPLOAD_MAX_CONNECTIONS_GLOBAL}")
            .contains("limit_req zone=upload_rate_per_ip burst=${UPLOAD_REQUEST_BURST} nodelay")
            .contains("limit_conn_status 429")
            .contains("limit_req_status 429")
            .contains("proxy_request_buffering off")
            .contains("proxy_pass http://minio:9000");
    }
}
