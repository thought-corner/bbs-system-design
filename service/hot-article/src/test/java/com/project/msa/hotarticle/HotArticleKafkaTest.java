package com.project.msa.hotarticle;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import(HotArticleTestConfig.class)
class HotArticleKafkaTest {

    private static final long ARTICLE_ID = 90_001L;
    private static final LocalDate CREATED_DATE = LocalDate.of(2027, 3, 1);
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(8_000_000_000_000_000_000L);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("네 토픽으로 발행한 이벤트가 소비되어 생성일 랭킹에 점수로 반영된다")
    void consumesEventsFromAllTopics() throws Exception {
        LocalDateTime createdAt = CREATED_DATE.atTime(9, 0);
        send(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(
                ARTICLE_ID, 5L, 1L, "제목", "본문", createdAt, createdAt, 1L));
        awaitCreated();

        send(EventType.ARTICLE_LIKED, new ArticleLikedEventPayload(ARTICLE_ID, 1L, 2L));
        send(EventType.COMMENT_CREATED, new CommentCreatedEventPayload(1L, ARTICLE_ID, "00000", false, 3L));
        send(EventType.ARTICLE_VIEWED, new ArticleViewedEventPayload(ARTICLE_ID, 100L));

        double expectedScore = 2 * 3 + 3 * 2 + 100;
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        Double score = null;
        while (System.nanoTime() < deadline) {
            score = redisTemplate.opsForZSet().score(HotArticleRedisRepository.rankingKey(CREATED_DATE),
                    String.valueOf(ARTICLE_ID));
            if (score != null && score == expectedScore) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(score).isEqualTo(expectedScore);
    }

    /** 생성 이벤트가 다른 토픽 이벤트보다 늦게 소비되면 그 이벤트들은 버려진다. 생성 반영을 먼저 기다린다. */
    private void awaitCreated() throws InterruptedException {
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        while (!Boolean.TRUE.equals(redisTemplate.hasKey(HotArticleRedisRepository.createdKey(ARTICLE_ID)))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ArticleCreated was not consumed within " + ARRIVAL_TIMEOUT);
            }
            Thread.sleep(100);
        }
    }

    private void send(EventType type, EventPayload payload) throws Exception {
        Event<EventPayload> event = Event.of(EVENT_SEQUENCE.incrementAndGet(), type, LocalDateTime.now(), payload);
        kafkaTemplate.send(type.topic(), String.valueOf(ARTICLE_ID), event.toJson()).get();
    }
}
