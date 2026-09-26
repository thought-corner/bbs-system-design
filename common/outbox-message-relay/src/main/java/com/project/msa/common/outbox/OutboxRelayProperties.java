package com.project.msa.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param pollInterval     폴링 주기 (기본 10초)
 * @param pendingThreshold 이만큼 넘게 남은 행만 폴링이 재발행한다 (기본 10초). 커밋 직후 발행과 겹치지 않게 한다
 * @param pollBatchSize    폴링 한 번에 가져올 최대 행 수
 * @param sendTimeout      Kafka 발행 한 건의 응답 대기 상한
 */
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(Duration pollInterval, Duration pendingThreshold, Integer pollBatchSize,
                                    Duration sendTimeout) {

    public OutboxRelayProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
        pendingThreshold = pendingThreshold == null ? Duration.ofSeconds(10) : pendingThreshold;
        pollBatchSize = pollBatchSize == null ? 100 : pollBatchSize;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(3) : sendTimeout;
    }
}
