package com.project.msa.common.outbox;

import com.project.msa.common.outbox.OutboxEventPublisher.OutboxSaved;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋 직후 비동기로 발행하고, 그 사이 장애로 남은 행은 폴링으로 재발행한다 (D11).
 * 발행에 성공한 행만 지우므로 유실은 없고 중복 발행은 있을 수 있다 — 소비자는 멱등이어야 한다.
 */
public class MessageRelay implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MessageRelay.class);
    private static final int SENDER_COUNT = 4;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private final OutboxMetrics outboxMetrics;
    /**
     * 같은 파티션 키의 이벤트는 늘 같은 단일 스레드로 보내 커밋 순서대로 나가게 한다.
     * 스레드 풀 하나에 섞으면 한 게시글의 이벤트끼리 순서가 뒤바뀐다 (D11).
     */
    private final List<ExecutorService> orderedSenders = IntStream.range(0, SENDER_COUNT)
            .mapToObj(sender -> Executors.newSingleThreadExecutor())
            .toList();

    MessageRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                 TransactionTemplate transactionTemplate, OutboxRelayProperties properties, Clock clock,
                 OutboxMetrics outboxMetrics) {
        this.outboxRepository = outboxRepository;
        this.outboxMetrics = outboxMetrics;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /** 커밋이 확정된 뒤에만 불린다. 발행 대기가 요청 스레드를 붙잡지 않게 별도 스레드로 넘긴다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publishAfterCommit(OutboxSaved outboxSaved) {
        Outbox outbox = outboxSaved.outbox();
        senderFor(outbox.partitionKey()).execute(() -> {
            boolean published = send(outbox);
            outboxMetrics.recordPublish(OutboxMetrics.AFTER_COMMIT, published);
            if (published) {
                outboxRepository.delete(outbox.outboxId());
            }
        });
    }

    /** `pendingThreshold`보다 오래 남은 행을 잠가 재발행한다. 잠금은 발행이 끝날 때까지 쥔다. */
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime pendingBefore = LocalDateTime.now(clock).minus(properties.pendingThreshold());
            List<Outbox> pendingOutboxes =
                    outboxRepository.lockPendingCreatedBefore(pendingBefore, properties.pollBatchSize());
            for (Outbox pendingOutbox : pendingOutboxes) {
                boolean published = send(pendingOutbox);
                outboxMetrics.recordPublish(OutboxMetrics.POLLING, published);
                if (published) {
                    outboxRepository.delete(pendingOutbox.outboxId());
                }
            }
        });
    }

    private boolean send(Outbox outbox) {
        try {
            kafkaTemplate.send(outbox.eventType().topic(), String.valueOf(outbox.partitionKey()), outbox.payload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception sendFailure) {
            if (sendFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("outbox publish failed, polling will retry: outboxId={}, type={}",
                    outbox.outboxId(), outbox.eventType(), sendFailure);
            return false;
        }
    }

    private ExecutorService senderFor(long partitionKey) {
        return orderedSenders.get(Math.floorMod(partitionKey, SENDER_COUNT));
    }

    @Override
    public void destroy() {
        orderedSenders.forEach(ExecutorService::shutdown);
    }
}
