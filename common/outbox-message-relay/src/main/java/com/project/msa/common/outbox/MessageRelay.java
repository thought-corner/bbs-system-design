package com.project.msa.common.outbox;

import com.project.msa.common.outbox.OutboxEventPublisher.OutboxSaved;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private final OutboxMetrics outboxMetrics;
    private final OutboxDeleter outboxDeleter;
    /**
     * 같은 파티션 키의 이벤트는 늘 같은 단일 스레드가 보내 커밋 순서대로 프로듀서에 들어가게 한다.
     * 스레드는 보내기 순서만 정하고 전송 완료를 기다리지 않는다. 파티션 안 순서는 멱등 프로듀서가 지킨다 (D11).
     */
    private final List<ExecutorService> orderedSenders;

    MessageRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                 TransactionTemplate transactionTemplate, OutboxRelayProperties properties, Clock clock,
                 OutboxMetrics outboxMetrics) {
        ProducerOrderingGuard.check(kafkaTemplate.getProducerFactory().getConfigurationProperties());
        this.outboxRepository = outboxRepository;
        this.outboxMetrics = outboxMetrics;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
        this.outboxDeleter = new OutboxDeleter(outboxRepository, properties.deleteBatchSize());
        this.orderedSenders = IntStream.range(0, properties.senderCount())
                .mapToObj(sender -> Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "outbox-sender-" + sender);
                    thread.setDaemon(true);
                    return thread;
                }))
                .toList();
    }

    /** 커밋이 확정된 뒤에만 불린다. 발행이 요청 스레드를 붙잡지 않게 파티션 키의 스레드로 넘긴다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publishAfterCommit(OutboxSaved outboxSaved) {
        Outbox outbox = outboxSaved.outbox();
        orderedSenders.get(SenderSelector.senderIndex(outbox.partitionKey(), orderedSenders.size())).execute(() ->
                send(outbox).whenComplete((sent, sendFailure) -> {
                    // 프로듀서 I/O 스레드다. DB를 부르지 않고 지울 ID만 넘긴다
                    boolean published = sendFailure == null;
                    outboxMetrics.recordPublish(OutboxMetrics.AFTER_COMMIT, published);
                    if (published) {
                        outboxDeleter.enqueue(outbox.outboxId());
                    } else {
                        logFailure(outbox, sendFailure);
                    }
                }));
    }

    /**
     * `pendingThreshold`보다 오래 남은 행을 잠가 재발행한다.
     * 잠근 행을 모두 보낸 뒤 결과를 한꺼번에 기다리고, 성공한 행만 같은 트랜잭션에서 지운다. 잠금은 그때까지 쥔다.
     */
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime pendingBefore = LocalDateTime.now(clock).minus(properties.pendingThreshold());
            List<Outbox> pendingOutboxes =
                    outboxRepository.lockPendingCreatedBefore(pendingBefore, properties.pollBatchSize());
            List<CompletableFuture<?>> sends = pendingOutboxes.stream()
                    .<CompletableFuture<?>>map(this::send)
                    .toList();
            long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
            List<Long> publishedIds = new ArrayList<>(pendingOutboxes.size());
            for (int i = 0; i < pendingOutboxes.size(); i++) {
                boolean published = awaitSent(sends.get(i), pendingOutboxes.get(i), deadline);
                outboxMetrics.recordPublish(OutboxMetrics.POLLING, published);
                if (published) {
                    publishedIds.add(pendingOutboxes.get(i).outboxId());
                }
            }
            outboxRepository.deleteAll(publishedIds);
        });
    }

    private CompletableFuture<?> send(Outbox outbox) {
        try {
            return kafkaTemplate.send(outbox.eventType().topic(), String.valueOf(outbox.partitionKey()), outbox.payload());
        } catch (RuntimeException sendFailure) {
            return CompletableFuture.failedFuture(sendFailure);
        }
    }

    private boolean awaitSent(CompletableFuture<?> sent, Outbox outbox, long deadline) {
        try {
            sent.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logFailure(outbox, interrupted);
            return false;
        } catch (ExecutionException | TimeoutException sendFailure) {
            logFailure(outbox, sendFailure);
            return false;
        }
    }

    private void logFailure(Outbox outbox, Throwable sendFailure) {
        log.warn("outbox publish failed, polling will retry: outboxId={}, type={}",
                outbox.outboxId(), outbox.eventType(), sendFailure);
    }

    /** 보내기 대기열을 비우고, 떠 있는 전송을 끝낸 뒤, 성공한 행을 지우고 멈춘다. 못 지운 행은 폴링 몫이다. */
    @Override
    public void destroy() {
        orderedSenders.forEach(ExecutorService::shutdown);
        for (ExecutorService sender : orderedSenders) {
            try {
                sender.awaitTermination(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            kafkaTemplate.flush();
        } catch (RuntimeException flushFailure) {
            log.warn("kafka flush failed on shutdown, polling will republish unsent rows", flushFailure);
        }
        outboxDeleter.close(properties.sendTimeout());
    }
}
