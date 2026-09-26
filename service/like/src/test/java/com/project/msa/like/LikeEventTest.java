package com.project.msa.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(LikeTestConfig.class)
class LikeEventTest {

    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(50_000);
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(20);
    /** 뒤따르는 이벤트가 도착할 때까지 모은다. 그 사이에 없어야 할 이벤트가 끼었는지 본다. */
    private static final Duration SETTLE_AFTER_LAST = Duration.ofSeconds(2);

    @Autowired
    private LikeService likeService;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Test
    @DisplayName("좋아요·취소하면 board-like 토픽에 articleId 키로 누적 좋아요 수를 담은 이벤트가 도착한다")
    void likedAndUnlikedEventsCarryCumulativeCount() {
        long articleId = ARTICLE_SEQUENCE.incrementAndGet();

        likeService.like(articleId, 1L);
        likeService.like(articleId, 2L);
        likeService.unlike(articleId, 1L);

        List<ConsumerRecord<String, String>> records = collectUntil(articleId,
                event -> event.type() == EventType.ARTICLE_UNLIKED);
        assertThat(records).allSatisfy(record -> assertThat(record.key()).isEqualTo(String.valueOf(articleId)));
        assertThat(records.stream().map(record -> Event.fromJson(record.value()).payload()).toList())
                .containsExactly(
                        new ArticleLikedEventPayload(articleId, 1L, 1L),
                        new ArticleLikedEventPayload(articleId, 2L, 2L),
                        new ArticleUnlikedEventPayload(articleId, 1L, 1L));
    }

    @Test
    @DisplayName("상태가 바뀌지 않은 중복 좋아요·중복 취소는 이벤트를 내지 않는다")
    void unchangedLikeStateProducesNoEvent() {
        long articleId = ARTICLE_SEQUENCE.incrementAndGet();

        likeService.like(articleId, 1L);
        likeService.like(articleId, 1L);
        likeService.unlike(articleId, 2L);
        likeService.unlike(articleId, 1L);
        likeService.unlike(articleId, 1L);
        // 마지막 표지. 이 이벤트까지 모은 목록에 중복 이벤트가 없어야 한다
        likeService.like(articleId, 3L);

        List<ConsumerRecord<String, String>> records = collectUntil(articleId,
                event -> event.payload() instanceof ArticleLikedEventPayload liked && liked.userId() == 3L);
        assertThat(records.stream().map(record -> Event.fromJson(record.value()).payload()).toList())
                .containsExactly(
                        new ArticleLikedEventPayload(articleId, 1L, 1L),
                        new ArticleUnlikedEventPayload(articleId, 1L, 0L),
                        new ArticleLikedEventPayload(articleId, 3L, 1L));
    }

    /** 한 게시글의 이벤트는 같은 파티션으로 가므로 발행 순서대로 모인다. 마지막 이벤트를 본 뒤 잠깐 더 모은다. */
    private List<ConsumerRecord<String, String>> collectUntil(long articleId, Predicate<Event<EventPayload>> lastEvent) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = boardLikeConsumer()) {
            long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
            Long settleDeadline = null;
            while (System.nanoTime() < (settleDeadline == null ? deadline : settleDeadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (!record.key().equals(String.valueOf(articleId))) {
                        continue;
                    }
                    collected.add(record);
                    if (settleDeadline == null && lastEvent.test(Event.fromJson(record.value()))) {
                        settleDeadline = System.nanoTime() + SETTLE_AFTER_LAST.toNanos();
                    }
                }
            }
            if (settleDeadline == null) {
                throw new AssertionError("last event for article " + articleId + " did not arrive within " + ARRIVAL_TIMEOUT);
            }
        }
        return collected;
    }

    private KafkaConsumer<String, String> boardLikeConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "like-event-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(EventType.Topic.BOARD_LIKE));
        return consumer;
    }
}
