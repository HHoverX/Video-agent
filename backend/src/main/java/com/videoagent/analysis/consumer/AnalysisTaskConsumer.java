package com.videoagent.analysis.consumer;

import com.videoagent.analysis.dto.AnalysisMessage;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;

import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
    topic = "${videoagent.analysis.topic}",
    consumerGroup = "${videoagent.analysis.consumer-group}"
)
public class AnalysisTaskConsumer implements
    RocketMQListener<AnalysisMessage>,
    RocketMQPushConsumerLifecycleListener {

    private static final int ROUTE_REFRESH_INTERVAL_MILLIS = 1_000;

    private final AnalysisTaskProcessor processor;

    public AnalysisTaskConsumer(AnalysisTaskProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void onMessage(AnalysisMessage message) {
        processor.process(message);
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // The broker may auto-create the single M3 topic on the first send. Refreshing
        // routes promptly keeps that first queued message from waiting for the client default.
        consumer.setPollNameServerInterval(ROUTE_REFRESH_INTERVAL_MILLIS);
    }
}
