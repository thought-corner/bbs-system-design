package com.project.msa.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outbox가 제때 비워지는지 보는 지표 (D11).
 * 남은 행 수와 가장 오래된 행의 나이가 오르면 발행이 밀리고 있다는 뜻이다.
 */
class OutboxMetrics {

    static final String AFTER_COMMIT = "after_commit";
    static final String POLLING = "polling";

    private final MeterRegistry meterRegistry;

    OutboxMetrics(MeterRegistry meterRegistry, OutboxRepository outboxRepository, Clock clock) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("outbox.pending", outboxRepository, OutboxRepository::countPending)
                .description("발행되지 않고 남은 outbox 행 수")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.age", outboxRepository, repository -> repository.findOldestCreatedAt()
                        .map(oldestCreatedAt -> Duration.between(oldestCreatedAt, LocalDateTime.now(clock)).toMillis() / 1000.0)
                        .orElse(0.0))
                .description("가장 오래 남은 outbox 행의 나이")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    void recordPublish(String path, boolean published) {
        Counter.builder("outbox.publish")
                .description("outbox 발행 시도")
                .tag("path", path)
                .tag("result", published ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }
}
