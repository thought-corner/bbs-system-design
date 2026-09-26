package com.project.msa.common.outbox;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 비즈니스 코드가 이벤트를 내는 유일한 입구. Kafka로 직접 보내지 않고 진행 중인 트랜잭션 안에서 `outbox`에 쓴다.
 * 발행은 커밋이 확정된 뒤 {@link MessageRelay}가 한다.
 */
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Snowflake snowflake;
    private final Clock clock;

    OutboxEventPublisher(OutboxRepository outboxRepository, ApplicationEventPublisher applicationEventPublisher,
                         Snowflake snowflake, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.snowflake = snowflake;
        this.clock = clock;
    }

    public void publish(EventType type, EventPayload payload, long partitionKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox event must be published inside the business transaction: " + type);
        }
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        Event<EventPayload> event = Event.of(snowflake.nextId(), type, occurredAt, payload);
        Outbox outbox = new Outbox(snowflake.nextId(), type, event.toJson(), partitionKey, occurredAt);
        outboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new OutboxSaved(outbox));
    }

    record OutboxSaved(Outbox outbox) {
    }
}
