package com.videoagent.analysis.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.junit.jupiter.api.Test;

class AnalysisTaskConsumerTest {

    @Test
    void shouldRefreshTopicRoutesPromptlyAfterConsumerStarts() {
        AnalysisTaskConsumer consumer = new AnalysisTaskConsumer(mock(AnalysisTaskProcessor.class));
        DefaultMQPushConsumer rocketConsumer = new DefaultMQPushConsumer();

        consumer.prepareStart(rocketConsumer);

        assertThat(rocketConsumer.getPollNameServerInterval()).isEqualTo(1_000);
    }
}
