package com.project.msa.articleread;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import(ArticleReadTestConfig.class)
class ArticleReadKafkaTest {

    private static final long BOARD_ID = 90_001L;
    private static final long ARTICLE_ID = 2_000_000_000_000_000_001L;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(8_000_000_000_000_000_000L);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ArticleReadRedisRepository articleReadRedisRepository;

    @Test
    @DisplayName("세 토픽으로 발행한 이벤트가 소비되어 상세 읽기 모델에 반영된다")
    void consumesEventsFromAllTopics() throws Exception {
        LocalDateTime writtenAt = LocalDateTime.of(2026, 9, 26, 10, 0);

        send(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(
                ARTICLE_ID, BOARD_ID, 7L, "제목", "본문", writtenAt, writtenAt, 1L));
        send(EventType.ARTICLE_LIKED, new ArticleLikedEventPayload(ARTICLE_ID, 1L, 2L));
        send(EventType.COMMENT_CREATED, new CommentCreatedEventPayload(1L, ARTICLE_ID, "00000", false, 3L));

        ArticleReadResponse expected = new ArticleReadResponse(ARTICLE_ID, BOARD_ID, 7L, "제목", "본문",
                writtenAt, writtenAt, 3L, 2L);
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        Optional<ArticleReadResponse> article = Optional.empty();
        while (System.nanoTime() < deadline) {
            article = articleReadRedisRepository.findArticle(ARTICLE_ID);
            if (article.filter(expected::equals).isPresent()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(article).contains(expected);
    }

    private void send(EventType type, EventPayload payload) throws Exception {
        Event<EventPayload> event = Event.of(EVENT_SEQUENCE.incrementAndGet(), type, LocalDateTime.now(), payload);
        kafkaTemplate.send(type.topic(), String.valueOf(ARTICLE_ID), event.toJson()).get();
    }
}
