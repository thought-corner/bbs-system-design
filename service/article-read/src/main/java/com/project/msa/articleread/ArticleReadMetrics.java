package com.project.msa.articleread;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 상세 조회가 읽기 모델에서 끝났는지, 원본까지 갔는지 보는 지표 (D12·D13).
 * 원본 비율이 오르면 읽기 모델이 비었거나 논리 만료가 몰린 것이다.
 */
@Component
class ArticleReadMetrics {

    static final String READ_MODEL = "read_model";
    static final String ORIGIN_REFRESH = "origin_refresh";
    static final String ORIGIN_PASSTHROUGH = "origin_passthrough";

    private final MeterRegistry meterRegistry;

    ArticleReadMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void recordSource(String source) {
        Counter.builder("article.read.source")
                .description("상세 조회 값을 가져온 곳")
                .tag("source", source)
                .register(meterRegistry)
                .increment();
    }

    void recordRefreshLock(boolean acquired) {
        Counter.builder("article.read.refresh.lock")
                .description("원본 갱신 락 시도")
                .tag("acquired", String.valueOf(acquired))
                .register(meterRegistry)
                .increment();
    }
}
