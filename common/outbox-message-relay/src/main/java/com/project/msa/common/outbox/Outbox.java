package com.project.msa.common.outbox;

import com.project.msa.common.event.EventType;
import java.time.LocalDateTime;

/** `outbox` 테이블의 한 행. `payload`는 이벤트 봉투 JSON이고 Kafka 메시지 값으로 그대로 나간다. */
record Outbox(long outboxId, EventType eventType, String payload, long partitionKey, LocalDateTime createdAt) {
}
