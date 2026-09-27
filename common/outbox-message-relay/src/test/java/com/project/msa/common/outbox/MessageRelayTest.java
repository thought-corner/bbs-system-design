package com.project.msa.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.outbox.OutboxEventPublisher.OutboxSaved;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageRelayTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long ARTICLE_ID = 97_482_243_928_129_536L;

    private final ControllableKafkaTemplate kafkaTemplate = new ControllableKafkaTemplate();
    private final RecordingOutboxRepository repository = new RecordingOutboxRepository();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MessageRelay messageRelay = MessageRelayFixture.relay(kafkaTemplate, repository, meterRegistry);

    @AfterEach
    void tearDown() {
        kafkaTemplate.pendingSends.forEach(sent -> sent.complete(null));
        messageRelay.destroy();
    }

    @Test
    @DisplayName("같은 게시글의 이벤트 20건은 앞선 전송 완료를 기다리지 않고 커밋 순서대로 모두 보내진다")
    void sendsWithoutWaitingForPreviousCompletionInCommitOrder() {
        LongStream.rangeClosed(1, 20).forEach(outboxId ->
                messageRelay.publishAfterCommit(new OutboxSaved(MessageRelayFixture.outbox(outboxId, ARTICLE_ID))));

        // 전송 완료를 하나도 주지 않았다. 건마다 기다린다면 첫 건에서 멈춰 1건만 보내진다
        awaitUntil(() -> kafkaTemplate.sentKeys.size() == 20);
        assertThat(kafkaTemplate.pendingSends).allMatch(sent -> !sent.isDone());
        assertThat(kafkaTemplate.sentKeys).containsOnly(String.valueOf(ARTICLE_ID));
        assertThat(kafkaTemplate.sentPayloads).containsExactlyElementsOf(LongStream.rangeClosed(1, 20)
                .mapToObj(outboxId -> "{\"outboxId\":" + outboxId + "}")
                .toList());
    }

    @Test
    @DisplayName("전송에 성공한 행만 지워지고, 실패한 행은 남아 실패 지표가 오른다")
    void deletesOnlyPublishedRows() {
        LongStream.rangeClosed(1, 10).forEach(outboxId ->
                messageRelay.publishAfterCommit(new OutboxSaved(MessageRelayFixture.outbox(outboxId, ARTICLE_ID + outboxId))));
        awaitUntil(() -> kafkaTemplate.pendingSends.size() == 10);

        for (int i = 0; i < 10; i++) {
            if (i < 6) {
                kafkaTemplate.pendingSends.get(i).complete(null);
            } else {
                kafkaTemplate.pendingSends.get(i).completeExceptionally(new IllegalStateException("broker down"));
            }
        }

        Set<Long> published = Set.copyOf(sentOutboxIds(0, 6));
        awaitUntil(() -> repository.deletedIds.equals(published));
        assertThat(publishCount("after_commit", "success")).isEqualTo(6);
        assertThat(publishCount("after_commit", "failure")).isEqualTo(4);
    }

    @Test
    @DisplayName("폴링은 잠근 행을 모두 보낸 뒤 결과를 모아 기다리고, 성공한 행만 지운다")
    void pollingSendsAllLockedRowsBeforeWaitingAndDeletesPublishedOnes() {
        List<Outbox> locked = LongStream.rangeClosed(1, 5)
                .mapToObj(outboxId -> MessageRelayFixture.outbox(outboxId, ARTICLE_ID + outboxId))
                .toList();
        repository.pending(locked);
        // 다섯 건이 모두 보내진 뒤에야 완료가 온다. 행마다 기다리면 첫 건이 제한 시간에 걸려 실패로 끝난다
        kafkaTemplate.afterEachSend(template -> {
            if (template.pendingSends.size() == 5) {
                for (int i = 0; i < 5; i++) {
                    if (i < 3) {
                        template.pendingSends.get(i).complete(null);
                    } else {
                        template.pendingSends.get(i).completeExceptionally(new IllegalStateException("broker down"));
                    }
                }
            }
        });

        messageRelay.publishPending();

        assertThat(repository.deletedIds).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(publishCount("polling", "success")).isEqualTo(3);
        assertThat(publishCount("polling", "failure")).isEqualTo(2);
    }

    private List<Long> sentOutboxIds(int fromIndex, int toIndex) {
        // 키마다 스레드가 달라 보낸 순서는 섞인다. 키(ARTICLE_ID + outboxId)에서 outboxId를 되찾는다
        return kafkaTemplate.sentKeys.subList(fromIndex, toIndex).stream()
                .map(key -> Long.parseLong(key) - ARTICLE_ID)
                .toList();
    }

    private double publishCount(String path, String result) {
        var counter = meterRegistry.find("outbox.publish").tag("path", path).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            Thread.onSpinWait();
        }
    }
}
