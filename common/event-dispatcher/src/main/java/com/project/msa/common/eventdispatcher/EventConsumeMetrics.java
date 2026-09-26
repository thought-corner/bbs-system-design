package com.project.msa.common.eventdispatcher;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 소비자가 최종적 일관성을 얼마나 늦게, 얼마나 버리며 맞추는지 보는 지표.
 * 이벤트의 `occurredAt`은 시간대 없는 발행 서비스의 로컬 시각이다. 발행·소비 서비스가 같은 시간대의 시스템 시계를 쓴다고 보고 비교한다.
 */
public class EventConsumeMetrics {

    private final MeterRegistry meterRegistry;
    private final Clock systemClock;

    /** @param systemClock 업무 시계(테스트가 옮기는 시계)가 아니라 실제 시스템 시계. 지연은 실제로 흐른 시간이어야 한다 */
    public EventConsumeMetrics(MeterRegistry meterRegistry, Clock systemClock) {
        this.meterRegistry = meterRegistry;
        this.systemClock = systemClock;
    }

    /** 이벤트 발생에서 소비자가 반영을 마칠 때까지. 시계 차이로 음수가 나오면 0으로 본다. */
    public void recordLag(Event<? extends EventPayload> event) {
        Duration lag = Duration.between(event.occurredAt(), LocalDateTime.now(systemClock));
        Timer.builder("event.consume.lag")
                .description("이벤트 발생에서 소비자 반영까지")
                .tag("type", event.type().name())
                .register(meterRegistry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    /** 늦게 왔거나(더 새 이벤트가 이미 반영됨) 삭제된 게시글의 것이라 반영하지 않은 이벤트. */
    public void recordStale(EventType type) {
        Counter.builder("event.consume.stale")
                .description("반영하지 않고 버린 이벤트")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
    }
}
