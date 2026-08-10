package com.videoagent.outbox.entity;

public enum OutboxEventStatus {
    PENDING,

    PUBLISHED,

    EXHAUSTED,

    CANCELLED,

    INVALID
}
