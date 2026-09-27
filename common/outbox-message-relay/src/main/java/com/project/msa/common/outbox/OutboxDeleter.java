package com.project.msa.common.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 발행에 성공한 outbox 행을 모아서 지운다.
 * 전송 완료 콜백은 Kafka 프로듀서의 I/O 스레드에서 불리므로 거기서 DB를 부르지 않고 ID만 넘긴다.
 * 지우기에 실패하면 행이 남을 뿐이고, 폴링이 다시 발행한다(중복 발행 — 소비자는 멱등이다).
 */
class OutboxDeleter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeleter.class);
    private static final long IDLE_POLL_MILLIS = 100;

    private final OutboxRepository outboxRepository;
    private final int batchSize;
    private final BlockingQueue<Long> publishedIds = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean closing;

    OutboxDeleter(OutboxRepository outboxRepository, int batchSize) {
        this.outboxRepository = outboxRepository;
        this.batchSize = batchSize;
        this.worker = new Thread(this::deleteUntilClosed, "outbox-deleter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 콜백 스레드에서 불린다. 막히지 않는다. */
    void enqueue(long outboxId) {
        publishedIds.add(outboxId);
    }

    private void deleteUntilClosed() {
        while (!closing || !publishedIds.isEmpty()) {
            try {
                Long first = publishedIds.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    List<Long> batch = new ArrayList<>(batchSize);
                    batch.add(first);
                    publishedIds.drainTo(batch, batchSize - 1);
                    deleteQuietly(batch);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deleteQuietly(List<Long> batch) {
        try {
            outboxRepository.deleteAll(batch);
        } catch (RuntimeException deleteFailure) {
            log.warn("published outbox rows were not deleted, polling will republish them: count={}", batch.size(),
                    deleteFailure);
        }
    }

    /** 이미 받은 ID를 제한 시간 안에서 모두 지우고 멈춘다. */
    void close(Duration timeout) {
        closing = true;
        try {
            worker.join(timeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
            log.warn("outbox deleter stopped with {} rows left, polling will republish them", publishedIds.size());
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }
}
