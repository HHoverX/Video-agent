package com.videoagent.analysis.producer;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.service.AnalysisProperties;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AnalysisTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskProducer.class);

    private final RocketMQTemplate rocketMQTemplate;
    private final AnalysisProperties properties;

    public AnalysisTaskProducer(RocketMQTemplate rocketMQTemplate, AnalysisProperties properties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
    }

    public void send(AnalysisMessage message) {
        SendResult result = rocketMQTemplate.syncSend(properties.topic(), message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ did not acknowledge the analysis message");
        }
        log.info("[taskId={}][videoId={}][stage=DISPATCH] analysis message sent",
            message.taskId(), message.videoId());
    }
}
