package com.project.msa.hotarticle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.hotarticle.HotArticleTestConfig.MovableClock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(HotArticleTestConfig.class)
class HotArticleTest {

    /** 테스트마다 다른 생성일을 써서 날짜별 랭킹이 섞이지 않게 한다. */
    private static final AtomicLong DAY_SEQUENCE = new AtomicLong();
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(1_000);
    /** Snowflake처럼 2^53을 넘는 eventId. Lua에서 숫자로 비교하면 정밀도를 잃는 크기다. */
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(7_000_000_000_000_000_000L);

    @Autowired
    private HotArticleEventHandler hotArticleEventHandler;

    @Autowired
    private HotArticleService hotArticleService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MovableClock movableClock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("당일 생성 게시글의 좋아요·댓글·조회 이벤트를 받으면 점수는 좋아요×3 + 댓글×2 + 조회×1이다")
    void scoreIsWeightedSumOfCounts() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);

        handle(liked(articleId, 1L, 4L));
        handle(commented(articleId, 5L));
        handle(viewed(articleId, 100L));

        assertThat(score(createdDate, articleId)).isEqualTo(4 * 3 + 5 * 2 + 100.0);
    }

    @Test
    @DisplayName("생성 이벤트를 받지 못한(당일 생성이 아닌) 게시글의 이벤트는 버린다")
    void ignoresArticleWithoutCreatedEvent() {
        LocalDate createdDate = newDay();
        long unknownArticleId = ARTICLE_SEQUENCE.incrementAndGet();

        handle(liked(unknownArticleId, 1L, 9L));
        handle(viewed(unknownArticleId, 500L));

        assertThat(score(createdDate, unknownArticleId)).isNull();
        assertThat(redisTemplate.hasKey(HotArticleRedisRepository.countKey(unknownArticleId, HotArticleMetric.LIKE)))
                .isFalse();
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 점수가 그대로다")
    void duplicateEventKeepsScore() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);
        Event<ArticleLikedEventPayload> liked = liked(articleId, 1L, 2L);
        Event<ArticleViewedEventPayload> viewed = viewed(articleId, 100L);

        handle(liked);
        handle(viewed);
        handle(liked);
        handle(viewed);

        assertThat(score(createdDate, articleId)).isEqualTo(2 * 3 + 100.0);
    }

    @Test
    @DisplayName("eventId가 더 작은 옛 좋아요·댓글 이벤트가 늦게 와도 카운터가 되돌아가지 않는다")
    void staleLikeAndCommentEventsAreIgnored() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);
        Event<ArticleLikedEventPayload> olderLike = liked(articleId, 1L, 1L);
        Event<ArticleUnlikedEventPayload> newerUnlike = unliked(articleId, 1L, 0L);
        Event<CommentCreatedEventPayload> olderComment = commented(articleId, 1L);
        Event<CommentCreatedEventPayload> newerComment = commented(articleId, 2L);

        handle(newerUnlike);
        handle(olderLike);
        handle(newerComment);
        handle(olderComment);

        assertThat(count(articleId, HotArticleMetric.LIKE)).isEqualTo("0");
        assertThat(count(articleId, HotArticleMetric.COMMENT)).isEqualTo("2");
        assertThat(score(createdDate, articleId)).isEqualTo(2 * 2.0);
    }

    @Test
    @DisplayName("조회수는 더 작은 누적값이 늦게 오면 eventId가 더 커도 큰 값을 유지한다")
    void viewCountKeepsLargestValue() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);

        handle(viewed(articleId, 200L));
        handle(viewed(articleId, 100L));

        assertThat(count(articleId, HotArticleMetric.VIEW)).isEqualTo("200");
        assertThat(score(createdDate, articleId)).isEqualTo(200.0);
    }

    @Test
    @DisplayName("랭킹은 상위 10건만 남고, 잘린 게시글도 다음 이벤트로 점수가 오르면 다시 들어온다")
    void rankingKeepsTopTenAndReadmits() {
        LocalDate createdDate = newDay();
        long trimmedArticleId = created(createdDate, 10L);
        handle(liked(trimmedArticleId, 1L, 1L));
        for (int rank = 0; rank < 10; rank++) {
            long articleId = created(createdDate, 10L);
            handle(liked(articleId, 1L, 10L + rank));
        }
        assertThat(redisTemplate.opsForZSet().size(HotArticleRedisRepository.rankingKey(createdDate))).isEqualTo(10);
        assertThat(score(createdDate, trimmedArticleId)).isNull();

        handle(liked(trimmedArticleId, 2L, 100L));

        assertThat(redisTemplate.opsForZSet().size(HotArticleRedisRepository.rankingKey(createdDate))).isEqualTo(10);
        assertThat(score(createdDate, trimmedArticleId)).isEqualTo(300.0);
    }

    @Test
    @DisplayName("삭제 이벤트를 받으면 집계 중 랭킹에서 빠지고 이후 그 게시글의 이벤트는 버린다")
    void deletedArticleLeavesRanking() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);
        handle(liked(articleId, 1L, 5L));

        handle(deleted(articleId, createdDate, 10L));
        handle(liked(articleId, 2L, 6L));

        assertThat(score(createdDate, articleId)).isNull();
        assertThat(redisTemplate.hasKey(HotArticleRedisRepository.createdKey(articleId))).isFalse();
    }

    @Test
    @DisplayName("확정은 전날 랭킹 상위 10건을 점수 내림차순 articleId·boardId 목록으로 저장하고, 두 번 돌려도 같다")
    void confirmCopiesTopTenInScoreOrder() {
        LocalDate createdDate = newDay();
        long lowArticleId = created(createdDate, 1L);
        long highArticleId = created(createdDate, 2L);
        long middleArticleId = created(createdDate, 3L);
        handle(liked(lowArticleId, 1L, 1L));
        handle(liked(highArticleId, 1L, 9L));
        handle(liked(middleArticleId, 1L, 5L));

        hotArticleService.confirm(createdDate);
        List<HotArticleResponse> firstConfirmed = hotArticleService.readConfirmed(Optional.of(createdDate));
        hotArticleService.confirm(createdDate);

        assertThat(firstConfirmed).containsExactly(
                new HotArticleResponse(highArticleId, 2L),
                new HotArticleResponse(middleArticleId, 3L),
                new HotArticleResponse(lowArticleId, 1L));
        assertThat(hotArticleService.readConfirmed(Optional.of(createdDate))).isEqualTo(firstConfirmed);
    }

    @Test
    @DisplayName("조회 API는 date를 주면 그날, 생략하면 01시 이후엔 전날·01시 전엔 전전날 목록, 없으면 빈 목록이다")
    void readApiPicksLatestConfirmedDate() throws Exception {
        LocalDate dayBeforeYesterday = LocalDate.of(2030, 1, 1);
        LocalDate yesterday = dayBeforeYesterday.plusDays(1);
        long olderArticleId = created(dayBeforeYesterday, 1L);
        long newerArticleId = created(yesterday, 2L);
        handle(liked(olderArticleId, 1L, 1L));
        handle(liked(newerArticleId, 1L, 1L));
        hotArticleService.confirm(dayBeforeYesterday);
        hotArticleService.confirm(yesterday);

        movableClock.moveTo(yesterday.plusDays(1).atTime(0, 30));
        List<HotArticleResponse> beforeConfirmHour = readApi(null);
        movableClock.moveTo(yesterday.plusDays(1).atTime(1, 30));
        List<HotArticleResponse> afterConfirmHour = readApi(null);

        assertThat(beforeConfirmHour).containsExactly(new HotArticleResponse(olderArticleId, 1L));
        assertThat(afterConfirmHour).containsExactly(new HotArticleResponse(newerArticleId, 2L));
        assertThat(readApi("20300101")).containsExactly(new HotArticleResponse(olderArticleId, 1L));
        assertThat(readApi("20291231")).isEmpty();
    }

    @Test
    @DisplayName("카운터·last-event-id·랭킹 키 TTL은 2일, 확정 목록 TTL은 30일이다")
    void keysExpireOnSchedule() {
        LocalDate createdDate = newDay();
        long articleId = created(createdDate, 10L);
        handle(liked(articleId, 1L, 1L));
        handle(viewed(articleId, 100L));

        hotArticleService.confirm(createdDate);

        long twoDays = TimeUnit.DAYS.toSeconds(2);
        assertThat(ttl(HotArticleRedisRepository.createdKey(articleId))).isBetween(twoDays - 10, twoDays);
        assertThat(ttl(HotArticleRedisRepository.countKey(articleId, HotArticleMetric.LIKE))).isBetween(twoDays - 10, twoDays);
        assertThat(ttl(HotArticleRedisRepository.lastEventIdKey(articleId, HotArticleMetric.LIKE)))
                .isBetween(twoDays - 10, twoDays);
        assertThat(ttl(HotArticleRedisRepository.countKey(articleId, HotArticleMetric.VIEW))).isBetween(twoDays - 10, twoDays);
        assertThat(ttl(HotArticleRedisRepository.rankingKey(createdDate))).isBetween(twoDays - 10, twoDays);
        long thirtyDays = TimeUnit.DAYS.toSeconds(30);
        assertThat(ttl(HotArticleRedisRepository.confirmedListKey(createdDate))).isBetween(thirtyDays - 10, thirtyDays);
    }

    private LocalDate newDay() {
        return LocalDate.of(2026, 1, 1).plusDays(DAY_SEQUENCE.incrementAndGet());
    }

    private long created(LocalDate createdDate, long boardId) {
        long articleId = ARTICLE_SEQUENCE.incrementAndGet();
        LocalDateTime createdAt = createdDate.atTime(10, 0);
        handle(event(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(
                articleId, boardId, 1L, "제목", "본문", createdAt, createdAt, 1L)));
        return articleId;
    }

    private Event<ArticleDeletedEventPayload> deleted(long articleId, LocalDate createdDate, long boardId) {
        LocalDateTime createdAt = createdDate.atTime(10, 0);
        return event(EventType.ARTICLE_DELETED, new ArticleDeletedEventPayload(
                articleId, boardId, 1L, "제목", "본문", createdAt, createdAt, 0L));
    }

    private Event<ArticleLikedEventPayload> liked(long articleId, long userId, long articleLikeCount) {
        return event(EventType.ARTICLE_LIKED, new ArticleLikedEventPayload(articleId, userId, articleLikeCount));
    }

    private Event<ArticleUnlikedEventPayload> unliked(long articleId, long userId, long articleLikeCount) {
        return event(EventType.ARTICLE_UNLIKED, new ArticleUnlikedEventPayload(articleId, userId, articleLikeCount));
    }

    private Event<CommentCreatedEventPayload> commented(long articleId, long articleCommentCount) {
        return event(EventType.COMMENT_CREATED,
                new CommentCreatedEventPayload(ARTICLE_SEQUENCE.incrementAndGet(), articleId, "00000", false, articleCommentCount));
    }

    private Event<ArticleViewedEventPayload> viewed(long articleId, long articleViewCount) {
        return event(EventType.ARTICLE_VIEWED, new ArticleViewedEventPayload(articleId, articleViewCount));
    }

    /** 만든 순서대로 eventId가 커진다. 늦게 도착하는 상황은 handle 순서로 만든다. */
    private <T extends EventPayload> Event<T> event(EventType type, T payload) {
        return Event.of(EVENT_SEQUENCE.incrementAndGet(), type, LocalDateTime.of(2026, 1, 1, 0, 0), payload);
    }

    private void handle(Event<? extends EventPayload> event) {
        hotArticleEventHandler.handle(event);
    }

    private Double score(LocalDate createdDate, long articleId) {
        return redisTemplate.opsForZSet().score(HotArticleRedisRepository.rankingKey(createdDate),
                String.valueOf(articleId));
    }

    private String count(long articleId, HotArticleMetric metric) {
        return redisTemplate.opsForValue().get(HotArticleRedisRepository.countKey(articleId, metric));
    }

    private long ttl(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    private List<HotArticleResponse> readApi(String date) throws Exception {
        var request = get("/v1/hot-articles");
        if (date != null) {
            request.param("date", date);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }
}
