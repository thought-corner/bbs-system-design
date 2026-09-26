package com.project.msa.articleread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.articleread.ArticleReadResponse.ArticleReadPageResponse;
import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
@Import(ArticleReadTestConfig.class)
class ArticleReadModelTest {

    private static final AtomicLong BOARD_SEQUENCE = new AtomicLong(1_000);
    /** Snowflake와 같은 크기(2^53 초과)의 게시글 ID. 같은 밀리초 안에서 순번만 다르다. */
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(1_000_000_000_000_000_000L);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(7_000_000_000_000_000_000L);
    private static final LocalDateTime WRITTEN_AT = LocalDateTime.of(2026, 9, 26, 10, 0);

    @Autowired
    private ArticleReadEventHandler articleReadEventHandler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("ArticleCreated를 받으면 상세에 게시글 필드가 그대로 나오고 댓글·좋아요 수는 0이다")
    void createdArticleIsReadable() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();

        handle(created(articleId, boardId, "제목", 1L));

        ArticleReadResponse article = read(boardId, articleId);
        assertThat(article).isEqualTo(new ArticleReadResponse(articleId, boardId, 7L, "제목", "본문",
                WRITTEN_AT, WRITTEN_AT, 0L, 0L));
    }

    @Test
    @DisplayName("ArticleUpdated는 제목·본문을 바꾸고, 옛 수정이 늦게 와도 되돌리지 않는다")
    void staleUpdateDoesNotRevert() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, "원래 제목", 1L));
        Event<ArticleUpdatedEventPayload> olderUpdate = updated(articleId, boardId, "첫 수정");
        Event<ArticleUpdatedEventPayload> newerUpdate = updated(articleId, boardId, "둘째 수정");

        handle(newerUpdate);
        handle(olderUpdate);

        assertThat(read(boardId, articleId).title()).isEqualTo("둘째 수정");
    }

    @Test
    @DisplayName("댓글·좋아요 생성·취소 이벤트는 누적값으로 반영되고, 옛 이벤트와 중복 이벤트는 무시된다")
    void countEventsApplyCumulativeValues() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, "제목", 1L));
        Event<ArticleLikedEventPayload> olderLike = liked(articleId, 1L);
        Event<ArticleLikedEventPayload> newerLike = liked(articleId, 2L);
        Event<CommentCreatedEventPayload> comment = commented(articleId, 3L);

        handle(newerLike);
        handle(olderLike);
        handle(comment);
        handle(comment);
        ArticleReadResponse beforeCancel = read(boardId, articleId);
        handle(unliked(articleId, 1L));
        handle(uncommented(articleId, 2L));

        assertThat(beforeCancel.articleLikeCount()).isEqualTo(2);
        assertThat(beforeCancel.articleCommentCount()).isEqualTo(3);
        ArticleReadResponse afterCancel = read(boardId, articleId);
        assertThat(afterCancel.articleLikeCount()).isEqualTo(1);
        assertThat(afterCancel.articleCommentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("세 토픽의 이벤트를 여러 스레드가 동시에 적용해도 필드마다 가장 큰 eventId의 값이 남는다")
    void concurrentEventsDoNotOverwriteOtherFields() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, "제목 0", 1L));
        List<Event<? extends EventPayload>> events = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            events.add(updated(articleId, boardId, "제목 " + i));
            events.add(liked(articleId, i));
            events.add(commented(articleId, i));
        }

        List<Callable<Void>> applications = new ArrayList<>();
        for (Event<? extends EventPayload> event : events) {
            applications.add(() -> {
                handle(event);
                return null;
            });
        }
        runConcurrently(applications);

        ArticleReadResponse article = read(boardId, articleId);
        assertThat(article.title()).isEqualTo("제목 30");
        assertThat(article.articleLikeCount()).isEqualTo(30);
        assertThat(article.articleCommentCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("카운트 이벤트가 ArticleCreated보다 먼저 와도 나중에 합쳐져 상세에 나온다")
    void countsArrivingBeforeCreatedAreMerged() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        Event<ArticleCreatedEventPayload> created = created(articleId, boardId, "제목", 1L);

        handle(liked(articleId, 4L));
        handle(commented(articleId, 2L));
        mockMvc.perform(get(articlePath(boardId, articleId))).andExpect(status().isNotFound());
        handle(created);

        ArticleReadResponse article = read(boardId, articleId);
        assertThat(article.articleLikeCount()).isEqualTo(4);
        assertThat(article.articleCommentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ArticleDeleted를 받으면 상세는 404, 목록에서 빠지고 늦게 온 생성·카운트 이벤트로 되살아나지 않는다")
    void deletedArticleStaysDeleted() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        Event<ArticleCreatedEventPayload> created = created(articleId, boardId, "제목", 1L);
        Event<ArticleLikedEventPayload> lateLike = liked(articleId, 1L);
        handle(created);

        handle(deleted(articleId, boardId, 0L));
        handle(created);
        handle(lateLike);

        mockMvc.perform(get(articlePath(boardId, articleId))).andExpect(status().isNotFound());
        assertThat(readPage(boardId, 1, 10).articles()).isEmpty();
        assertThat(redisTemplate.opsForZSet().size(ArticleReadRedisRepository.boardArticleListKey(boardId))).isZero();
    }

    @Test
    @DisplayName("게시판 목록은 최신 1,000건만 남는다")
    void boardListKeepsLatestThousand() {
        long boardId = newBoard();
        long oldestArticleId = newArticleId();
        handle(created(oldestArticleId, boardId, "가장 오래된 글", 1L));
        for (int i = 2; i <= ArticleReadRedisRepository.BOARD_ARTICLE_LIST_SIZE; i++) {
            handle(created(newArticleId(), boardId, "글 " + i, i));
        }
        String listKey = ArticleReadRedisRepository.boardArticleListKey(boardId);
        assertThat(redisTemplate.opsForZSet().rank(listKey, String.valueOf(oldestArticleId))).isNotNull();

        handle(created(newArticleId(), boardId, "1,001번째 글", 1_001L));

        assertThat(redisTemplate.opsForZSet().size(listKey)).isEqualTo(1_000);
        assertThat(redisTemplate.opsForZSet().rank(listKey, String.valueOf(oldestArticleId))).isNull();
    }

    @Test
    @DisplayName("페이지 번호 목록은 최신순이고 페이지 경계가 정확하며 게시판 게시글 수를 준다")
    void pageListIsNewestFirst() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirst = createMany(boardId, 7);

        assertThat(idsOf(readPage(boardId, 1, 3).articles())).containsExactlyElementsOf(newestFirst.subList(0, 3));
        assertThat(idsOf(readPage(boardId, 2, 3).articles())).containsExactlyElementsOf(newestFirst.subList(3, 6));
        assertThat(idsOf(readPage(boardId, 3, 3).articles())).containsExactlyElementsOf(newestFirst.subList(6, 7));
        assertThat(readPage(boardId, 1, 3).articleCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("커서로 끝까지 이어 읽은 결과는 페이지 번호로 전부 읽은 결과와 같다 (같은 점수로 뭉치는 큰 ID 포함)")
    void scrollToEndMatchesAllPages() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirst = createMany(boardId, 11);

        List<Long> scrolledIds = new ArrayList<>();
        Long lastArticleId = null;
        while (true) {
            List<ArticleReadResponse> scroll = readScroll(boardId, 4, lastArticleId);
            if (scroll.isEmpty()) {
                break;
            }
            scrolledIds.addAll(idsOf(scroll));
            lastArticleId = scroll.get(scroll.size() - 1).articleId();
        }

        assertThat(scrolledIds).containsExactlyElementsOf(newestFirst);
    }

    @Test
    @DisplayName("다른 boardId 경로로 상세를 조회하면 404다")
    void otherBoardPathIsNotFound() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, "제목", 1L));

        mockMvc.perform(get(articlePath(newBoard(), articleId))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("게시판 게시글 수는 옛 이벤트가 늦게 와도 되돌아가지 않는다")
    void boardArticleCountIgnoresStaleEvents() throws Exception {
        long boardId = newBoard();
        long firstArticleId = newArticleId();
        long secondArticleId = newArticleId();
        Event<ArticleCreatedEventPayload> firstCreated = created(firstArticleId, boardId, "첫 글", 1L);
        Event<ArticleCreatedEventPayload> secondCreated = created(secondArticleId, boardId, "둘째 글", 2L);

        handle(secondCreated);
        handle(firstCreated);

        assertThat(readPage(boardId, 1, 10).articleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("상세 Hash의 TTL은 1일이고 게시판 목록 ZSET은 만료가 없다")
    void keysExpireOnSchedule() {
        long boardId = newBoard();
        long articleId = newArticleId();

        handle(created(articleId, boardId, "제목", 1L));

        long oneDay = TimeUnit.DAYS.toSeconds(1);
        assertThat(redisTemplate.getExpire(ArticleReadRedisRepository.articleKey(articleId), TimeUnit.SECONDS))
                .isBetween(oneDay - 10, oneDay);
        assertThat(redisTemplate.getExpire(ArticleReadRedisRepository.boardArticleListKey(boardId))).isEqualTo(-1);
    }

    @Test
    @DisplayName("디스패처는 게시글·댓글·좋아요 7종만 맡고 그 밖의 타입은 예외 없이 버린다")
    void dispatcherCoversReadModelEventTypes() {
        assertThat(articleReadEventHandler.supportedTypes()).containsExactlyInAnyOrder(
                EventType.ARTICLE_CREATED, EventType.ARTICLE_UPDATED, EventType.ARTICLE_DELETED,
                EventType.COMMENT_CREATED, EventType.COMMENT_DELETED,
                EventType.ARTICLE_LIKED, EventType.ARTICLE_UNLIKED);

        long articleId = newArticleId();
        handle(event(EventType.ARTICLE_VIEWED, new ArticleViewedEventPayload(articleId, 100L)));

        assertThat(redisTemplate.hasKey(ArticleReadRedisRepository.articleKey(articleId))).isFalse();
    }

    @Test
    @DisplayName("같은 이벤트 타입을 맡는 처리기가 둘이면 디스패처를 만들 수 없다")
    void rejectsTwoProcessorsForSameType() {
        List<ArticleReadEventProcessor<?>> conflicting = List.of(
                new StubProcessor(EventType.ARTICLE_LIKED), new StubProcessor(EventType.ARTICLE_LIKED));

        assertThatThrownBy(() -> new ArticleReadEventHandler(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTICLE_LIKED");
    }

    private record StubProcessor(EventType supportedType) implements ArticleReadEventProcessor<EventPayload> {

        @Override
        public void process(Event<EventPayload> event) {
        }
    }

    private long newBoard() {
        return BOARD_SEQUENCE.incrementAndGet();
    }

    private long newArticleId() {
        return ARTICLE_SEQUENCE.incrementAndGet();
    }

    /** @return 최신순 ID */
    private List<Long> createMany(long boardId, int count) {
        List<Long> newestFirst = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long articleId = newArticleId();
            handle(created(articleId, boardId, "글 " + i, i));
            newestFirst.add(0, articleId);
        }
        return newestFirst;
    }

    private Event<ArticleCreatedEventPayload> created(long articleId, long boardId, String title,
                                                      long boardArticleCount) {
        return event(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(articleId, boardId, 7L, title, "본문",
                WRITTEN_AT, WRITTEN_AT, boardArticleCount));
    }

    private Event<ArticleUpdatedEventPayload> updated(long articleId, long boardId, String title) {
        return event(EventType.ARTICLE_UPDATED, new ArticleUpdatedEventPayload(articleId, boardId, 7L, title, "본문",
                WRITTEN_AT, WRITTEN_AT.plusMinutes(1), 1L));
    }

    private Event<ArticleDeletedEventPayload> deleted(long articleId, long boardId, long boardArticleCount) {
        return event(EventType.ARTICLE_DELETED, new ArticleDeletedEventPayload(articleId, boardId, 7L, "제목", "본문",
                WRITTEN_AT, WRITTEN_AT, boardArticleCount));
    }

    private Event<ArticleLikedEventPayload> liked(long articleId, long articleLikeCount) {
        return event(EventType.ARTICLE_LIKED, new ArticleLikedEventPayload(articleId, 1L, articleLikeCount));
    }

    private Event<ArticleUnlikedEventPayload> unliked(long articleId, long articleLikeCount) {
        return event(EventType.ARTICLE_UNLIKED, new ArticleUnlikedEventPayload(articleId, 1L, articleLikeCount));
    }

    private Event<CommentCreatedEventPayload> commented(long articleId, long articleCommentCount) {
        return event(EventType.COMMENT_CREATED,
                new CommentCreatedEventPayload(newArticleId(), articleId, "00000", false, articleCommentCount));
    }

    private Event<CommentDeletedEventPayload> uncommented(long articleId, long articleCommentCount) {
        return event(EventType.COMMENT_DELETED,
                new CommentDeletedEventPayload(newArticleId(), articleId, "00000", true, articleCommentCount));
    }

    /** 만든 순서대로 eventId가 커진다. 늦게 도착하는 상황은 handle 순서로 만든다. */
    private <T extends EventPayload> Event<T> event(EventType type, T payload) {
        return Event.of(EVENT_SEQUENCE.incrementAndGet(), type, WRITTEN_AT, payload);
    }

    private void handle(Event<? extends EventPayload> event) {
        articleReadEventHandler.handle(event);
    }

    private String articlePath(long boardId, long articleId) {
        return "/v1/boards/" + boardId + "/articles/" + articleId;
    }

    private ArticleReadResponse read(long boardId, long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get(articlePath(boardId, articleId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleReadResponse.class);
    }

    private ArticleReadPageResponse readPage(long boardId, long page, long pageSize) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/boards/" + boardId + "/articles")
                        .param("page", String.valueOf(page))
                        .param("pageSize", String.valueOf(pageSize)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleReadPageResponse.class);
    }

    private List<ArticleReadResponse> readScroll(long boardId, long pageSize, Long lastArticleId) throws Exception {
        var request = get("/v1/boards/" + boardId + "/articles/infinite-scroll")
                .param("pageSize", String.valueOf(pageSize));
        if (lastArticleId != null) {
            request.param("lastArticleId", String.valueOf(lastArticleId));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private List<Long> idsOf(List<ArticleReadResponse> articles) {
        return articles.stream().map(ArticleReadResponse::articleId).toList();
    }

    private void runConcurrently(List<Callable<Void>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Void>> running = new ArrayList<>();
        for (Callable<Void> action : actions) {
            running.add(executor.submit(() -> {
                startSignal.await();
                return action.call();
            }));
        }
        startSignal.countDown();
        List<Throwable> failures = new ArrayList<>();
        for (Future<Void> action : running) {
            try {
                action.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                failures.add(failure.getCause());
            }
        }
        executor.shutdown();
        assertThat(failures).isEmpty();
    }
}
