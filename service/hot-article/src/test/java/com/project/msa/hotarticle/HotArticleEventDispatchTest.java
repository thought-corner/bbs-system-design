package com.project.msa.hotarticle;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(HotArticleTestConfig.class)
class HotArticleEventDispatchTest {

    private static final long ARTICLE_ID = 70_001L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2027, 6, 1, 10, 0);

    @Autowired
    private HotArticleEventHandler hotArticleEventHandler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("맡은 처리기가 없는 이벤트(게시글 수정)는 예외 없이 버려지고 Redis 상태가 바뀌지 않는다")
    void eventWithoutProcessorIsIgnored() {
        hotArticleEventHandler.handle(Event.of(1L, EventType.ARTICLE_CREATED, CREATED_AT,
                new ArticleCreatedEventPayload(ARTICLE_ID, 3L, 1L, "제목", "본문", CREATED_AT, CREATED_AT, 1L)));
        Set<String> keysBefore = redisTemplate.keys("hot-article::article::" + ARTICLE_ID + "::*");
        String createdBefore = redisTemplate.opsForValue().get(HotArticleRedisRepository.createdKey(ARTICLE_ID));

        hotArticleEventHandler.handle(Event.of(2L, EventType.ARTICLE_UPDATED, CREATED_AT.plusHours(1),
                new ArticleUpdatedEventPayload(ARTICLE_ID, 99L, 1L, "바뀐 제목", "본문", CREATED_AT,
                        CREATED_AT.plusHours(1), 1L)));

        assertThat(redisTemplate.keys("hot-article::article::" + ARTICLE_ID + "::*")).isEqualTo(keysBefore);
        assertThat(redisTemplate.opsForValue().get(HotArticleRedisRepository.createdKey(ARTICLE_ID)))
                .isEqualTo(createdBefore);
    }

    @Test
    @DisplayName("등록된 처리기가 맡는 이벤트 타입은 점수에 쓰는 7종이다")
    void processorsCoverScoringEventTypes() {
        assertThat(hotArticleEventHandler.supportedTypes()).containsExactlyInAnyOrder(
                EventType.ARTICLE_CREATED, EventType.ARTICLE_DELETED,
                EventType.COMMENT_CREATED, EventType.COMMENT_DELETED,
                EventType.ARTICLE_LIKED, EventType.ARTICLE_UNLIKED,
                EventType.ARTICLE_VIEWED);
    }
}
