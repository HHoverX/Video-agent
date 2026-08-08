package com.videoagent.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_INFRA_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureBackedHealthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldStartWithLocalInfrastructureAndServeHealth() {
        ResponseEntity<HealthResponse> apiHealth = restTemplate.getForEntity(
            "http://127.0.0.1:" + port + "/api/health",
            HealthResponse.class
        );
        ResponseEntity<String> actuatorHealth = restTemplate.getForEntity(
            "http://127.0.0.1:" + port + "/actuator/health",
            String.class
        );

        assertThat(apiHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiHealth.getBody()).isNotNull();
        assertThat(apiHealth.getBody().status()).isEqualTo("UP");
        assertThat(actuatorHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuatorHealth.getBody()).contains("\"status\":\"UP\"");
    }
}
