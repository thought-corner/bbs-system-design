package com.project.msa.common.outbox;

import com.project.msa.common.event.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

final class MessageRelayFixture {

    static final Duration SEND_TIMEOUT = Duration.ofSeconds(2);

    private MessageRelayFixture() {
    }

    static MessageRelay relay(ControllableKafkaTemplate kafkaTemplate, RecordingOutboxRepository repository) {
        return relay(kafkaTemplate, repository, new SimpleMeterRegistry());
    }

    static MessageRelay relay(ControllableKafkaTemplate kafkaTemplate, RecordingOutboxRepository repository,
                              MeterRegistry meterRegistry) {
        OutboxRelayProperties properties = new OutboxRelayProperties(null, Duration.ZERO, 100, SEND_TIMEOUT, 10, 4, 500);
        Clock clock = Clock.systemUTC();
        return new MessageRelay(repository, kafkaTemplate, new TransactionTemplate(new NoOpTransactionManager()),
                properties, clock, new OutboxMetrics(meterRegistry, repository, clock, 10));
    }

    static Outbox outbox(long outboxId, long partitionKey) {
        return new Outbox(outboxId, EventType.ARTICLE_LIKED, "{\"outboxId\":" + outboxId + "}", partitionKey,
                LocalDateTime.now());
    }

    /** 폴링의 트랜잭션 경계만 흉내 낸다. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
