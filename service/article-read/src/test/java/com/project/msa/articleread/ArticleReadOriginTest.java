package com.project.msa.articleread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.articleread.ArticleReadOriginTestConfig.MovableClock;
import com.project.msa.articleread.ArticleReadResponse.ArticleReadPageResponse;
import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ArticleReadTestConfig.class, ArticleReadOriginTestConfig.class})
class ArticleReadOriginTest {

    private static final String ARTICLE_ORIGIN = "http://localhost:8081";
    private static final String COMMENT_ORIGIN = "http://localhost:8082";
    private static final String LIKE_ORIGIN = "http://localhost:8083";
    private static final String VIEW_ORIGIN = "http://localhost:8084";
    private static final Duration PAST_LOGICAL_TTL = Duration.ofMinutes(11);

    private static final AtomicLong BOARD_SEQUENCE = new AtomicLong(5_000);
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(3_000_000_000_000_000_000L);
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(7_500_000_000_000_000_000L);
    private static final LocalDateTime WRITTEN_AT = LocalDateTime.of(2026, 9, 26, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer originServer;

    @Autowired
    private ArticleReadEventHandler articleReadEventHandler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MovableClock movableClock;

    @BeforeEach
    void setUp() {
        originServer.reset();
    }

    @Test
    @DisplayName("읽기 모델에 없는 게시글은 원본으로 채워 응답하고, 10분 안의 두 번째 조회는 원본을 부르지 않는다")
    void missIsFilledFromOriginOnce() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        expectOriginArticle(boardId, articleId, "원본 제목");
        expectCommentCount(articleId, 3L);
        expectLikeCount(articleId, 4L);

        ArticleReadDetailResponse first = read(boardId, articleId, null);
        ArticleReadDetailResponse second = read(boardId, articleId, null);

        originServer.verify();
        assertThat(first.title()).isEqualTo("원본 제목");
        assertThat(first.articleCommentCount()).isEqualTo(3);
        assertThat(first.articleLikeCount()).isEqualTo(4);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("논리 만료가 지난 뒤 첫 조회는 원본으로 다시 맞춘다")
    void expiredModelIsRefreshedFromOrigin() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        fillFromOrigin(boardId, articleId, 5L);
        movableClock.advance(Duration.ofMinutes(9));
        assertThat(read(boardId, articleId, null).articleLikeCount()).isEqualTo(5);
        originServer.verify();

        passTimeBeyondLogicalTtl(articleId, Duration.ofMinutes(2));
        originServer.reset();
        expectOriginArticle(boardId, articleId, "제목");
        expectCommentCount(articleId, 0L);
        expectLikeCount(articleId, 7L);

        assertThat(read(boardId, articleId, null).articleLikeCount()).isEqualTo(7);
        originServer.verify();
    }

    @Test
    @DisplayName("ArticleCreated로 만든 모델은 10분 안에는 원본을 부르지 않고, 그 뒤 첫 조회에 원본으로 맞춘다")
    void createdModelIsTrustedForLogicalTtl() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, 1L));

        assertThat(read(boardId, articleId, null).title()).isEqualTo("이벤트 제목");
        originServer.verify();

        movableClock.advance(PAST_LOGICAL_TTL);
        expectOriginArticle(boardId, articleId, "원본 제목");
        expectCommentCount(articleId, 0L);
        expectLikeCount(articleId, 0L);

        assertThat(read(boardId, articleId, null).title()).isEqualTo("원본 제목");
        originServer.verify();
    }

    @Test
    @DisplayName("논리 만료된 게시글에 여러 요청이 동시에 와도 원본 호출은 1번이고 모든 요청이 성공한다")
    void concurrentReadsRefreshOnce() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        fillFromOrigin(boardId, articleId, 1L);
        passTimeBeyondLogicalTtl(articleId, PAST_LOGICAL_TTL);
        originServer.reset();
        expectOriginArticle(boardId, articleId, "제목");
        expectCommentCount(articleId, 0L);
        expectLikeCount(articleId, 2L);

        List<Callable<Integer>> reads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reads.add(() -> mockMvc.perform(get(articlePath(boardId, articleId))).andReturn()
                    .getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(reads);

        originServer.verify();
        assertThat(statuses).hasSize(20).allMatch(status -> status == 200);
        assertThat(read(boardId, articleId, null).articleLikeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("원본을 읽는 사이 더 새 이벤트가 반영된 필드는 원본 값으로 덮어쓰지 않는다")
    void newerEventDuringOriginReadIsKept() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, 1L));
        movableClock.advance(PAST_LOGICAL_TTL);
        expectOriginArticle(boardId, articleId, "원본 제목");
        expectCommentCount(articleId, 3L);
        originServer.expect(once(), requestTo(LIKE_ORIGIN + "/v1/articles/" + articleId + "/likes/count"))
                .andRespond(request -> {
                    // 원본이 옛 좋아요 수(1)를 돌려주기 전에 더 새 좋아요 이벤트(9)가 읽기 모델에 반영된다
                    handle(event(EventType.ARTICLE_LIKED, new ArticleLikedEventPayload(articleId, 1L, 9L)));
                    return withSuccess(json(Map.of("articleId", articleId, "likeCount", 1L)),
                            MediaType.APPLICATION_JSON).createResponse(request);
                });

        ArticleReadDetailResponse refreshed = read(boardId, articleId, null);

        originServer.verify();
        assertThat(refreshed.articleLikeCount()).isEqualTo(9);
        assertThat(refreshed.articleCommentCount()).isEqualTo(3);
        assertThat(refreshed.title()).isEqualTo("원본 제목");
    }

    @Test
    @DisplayName("원본에 없는 게시글은 404이고 읽기 모델에 아무것도 쓰지 않는다")
    void missingInOriginIsNotFound() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        originServer.expect(once(), requestTo(ARTICLE_ORIGIN + articlePath(boardId, articleId)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        mockMvc.perform(get(articlePath(boardId, articleId))).andExpect(status().isNotFound());

        originServer.verify();
        assertThat(redisTemplate.hasKey(ArticleReadRedisRepository.articleKey(articleId))).isFalse();
    }

    @Test
    @DisplayName("삭제 표시된 게시글은 원본을 부르지 않고 404다")
    void deletedArticleDoesNotCallOrigin() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, 1L));
        handle(event(EventType.ARTICLE_DELETED, new ArticleDeletedEventPayload(articleId, boardId, 7L, "이벤트 제목",
                "본문", WRITTEN_AT, WRITTEN_AT, 0L)));
        movableClock.advance(PAST_LOGICAL_TTL);

        mockMvc.perform(get(articlePath(boardId, articleId))).andExpect(status().isNotFound());

        originServer.verify();
    }

    @Test
    @DisplayName("userId를 주면 view가 돌려준 조회수가 실리고, view가 실패하면 조회수만 비고 200이다")
    void detailCarriesViewCount() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        handle(created(articleId, boardId, 1L));
        originServer.expect(once(), requestTo(VIEW_ORIGIN + "/v1/articles/" + articleId + "/views/users/11"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json(Map.of("articleId", articleId, "viewCount", 42L)),
                        MediaType.APPLICATION_JSON));
        originServer.expect(once(), requestTo(VIEW_ORIGIN + "/v1/articles/" + articleId + "/views/users/12"))
                .andRespond(withServerError());

        ArticleReadDetailResponse viewed = read(boardId, articleId, 11L);
        ArticleReadDetailResponse viewFailed = read(boardId, articleId, 12L);

        originServer.verify();
        assertThat(viewed.viewCount()).isEqualTo(42);
        assertThat(viewFailed.viewCount()).isNull();
        assertThat(viewFailed.title()).isEqualTo("이벤트 제목");
    }

    @Test
    @DisplayName("요청 범위가 최신 목록 ZSET 안이면 페이지 목록은 원본을 부르지 않는다")
    void pageWithinBoardListSkipsOrigin() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirst = createMany(boardId, 5, 1_500L);

        ArticleReadPageResponse firstPage = readPage(boardId, 1, 3);

        originServer.verify();
        assertThat(firstPage.articles()).extracting(ArticleReadResponse::articleId)
                .containsExactlyElementsOf(newestFirst.subList(0, 3));
        assertThat(firstPage.articleCount()).isEqualTo(1_500);
    }

    @Test
    @DisplayName("ZSET 밖 페이지는 article 원본 목록을 부르고, 읽기 모델에 없는 글만 댓글·좋아요 수를 원본에서 채운다")
    void pageBeyondBoardListUsesOrigin() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirst = createMany(boardId, 5, 1_500L);
        long cachedArticleId = newestFirst.get(4);
        long olderArticleId = newArticleId();
        long oldestArticleId = newArticleId();
        originServer.expect(once(), requestTo(ARTICLE_ORIGIN + "/v1/boards/" + boardId + "/articles?page=2&pageSize=3"))
                .andRespond(withSuccess(json(Map.of(
                        "articles", List.of(body(cachedArticleId, boardId, "이벤트 제목"),
                                body(olderArticleId, boardId, "오래된 글"), body(oldestArticleId, boardId, "더 오래된 글")),
                        "articleCount", 1_500L)), MediaType.APPLICATION_JSON));
        expectCommentCount(olderArticleId, 1L);
        expectLikeCount(olderArticleId, 2L);
        expectCommentCount(oldestArticleId, 3L);
        expectLikeCount(oldestArticleId, 4L);

        ArticleReadPageResponse secondPage = readPage(boardId, 2, 3);

        originServer.verify();
        assertThat(secondPage.articles()).extracting(ArticleReadResponse::articleId)
                .containsExactly(cachedArticleId, olderArticleId, oldestArticleId);
        assertThat(secondPage.articles().get(1).articleLikeCount()).isEqualTo(2);
        assertThat(secondPage.articles().get(2).articleCommentCount()).isEqualTo(3);
        assertThat(secondPage.articleCount()).isEqualTo(1_500);
    }

    @Test
    @DisplayName("ZSET이 모자란 커서 목록은 article 원본 커서 목록으로 이어 읽는다")
    void scrollBeyondBoardListUsesOrigin() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirst = createMany(boardId, 3, 1_500L);
        long lastCachedArticleId = newestFirst.get(2);
        long olderArticleId = newArticleId();
        originServer.expect(once(), requestTo(ARTICLE_ORIGIN + "/v1/boards/" + boardId
                        + "/articles/infinite-scroll?pageSize=2&lastArticleId=" + lastCachedArticleId))
                .andRespond(withSuccess(json(List.of(body(olderArticleId, boardId, "오래된 글"))),
                        MediaType.APPLICATION_JSON));
        expectCommentCount(olderArticleId, 0L);
        expectLikeCount(olderArticleId, 5L);

        List<ArticleReadResponse> scroll = readScroll(boardId, 2, lastCachedArticleId);

        originServer.verify();
        assertThat(scroll).extracting(ArticleReadResponse::articleId).containsExactly(olderArticleId);
        assertThat(scroll.get(0).articleLikeCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("갱신 락 키의 TTL은 3초다")
    void refreshLockExpiresInThreeSeconds() throws Exception {
        long boardId = newBoard();
        long articleId = newArticleId();
        fillFromOrigin(boardId, articleId, 0L);

        Long lockTtlSeconds = redisTemplate.getExpire(ArticleReadRedisRepository.refreshLockKey(articleId),
                TimeUnit.SECONDS);

        assertThat(lockTtlSeconds).isBetween(1L, 3L);
    }

    /**
     * 가짜 시계만 옮기면 Redis의 갱신 락(실제 3초)은 그대로 남는다.
     * 논리 만료(10분)가 지날 만큼 시간이 흘렀다면 락도 이미 풀렸으므로 함께 지운다.
     */
    private void passTimeBeyondLogicalTtl(long articleId, Duration elapsed) {
        movableClock.advance(elapsed);
        redisTemplate.delete(ArticleReadRedisRepository.refreshLockKey(articleId));
    }

    private void fillFromOrigin(long boardId, long articleId, long likeCount) throws Exception {
        expectOriginArticle(boardId, articleId, "제목");
        expectCommentCount(articleId, 0L);
        expectLikeCount(articleId, likeCount);
        read(boardId, articleId, null);
        originServer.verify();
    }

    private void expectOriginArticle(long boardId, long articleId, String title) {
        originServer.expect(once(), requestTo(ARTICLE_ORIGIN + articlePath(boardId, articleId)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json(body(articleId, boardId, title)), MediaType.APPLICATION_JSON));
    }

    private void expectCommentCount(long articleId, long commentCount) {
        originServer.expect(once(), requestTo(COMMENT_ORIGIN + "/v1/articles/" + articleId + "/comments/count"))
                .andRespond(withSuccess(json(Map.of("articleId", articleId, "commentCount", commentCount)),
                        MediaType.APPLICATION_JSON));
    }

    private void expectLikeCount(long articleId, long likeCount) {
        originServer.expect(once(), requestTo(LIKE_ORIGIN + "/v1/articles/" + articleId + "/likes/count"))
                .andRespond(withSuccess(json(Map.of("articleId", articleId, "likeCount", likeCount)),
                        MediaType.APPLICATION_JSON));
    }

    private ArticleBody body(long articleId, long boardId, String title) {
        return new ArticleBody(articleId, boardId, 7L, title, "본문", WRITTEN_AT, WRITTEN_AT);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private long newBoard() {
        return BOARD_SEQUENCE.incrementAndGet();
    }

    private long newArticleId() {
        return ARTICLE_SEQUENCE.incrementAndGet();
    }

    /** @return 최신순 ID. 게시판 게시글 수는 ZSET보다 많은 값으로 둔다 */
    private List<Long> createMany(long boardId, int count, long boardArticleCount) {
        List<Long> newestFirst = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long articleId = newArticleId();
            handle(created(articleId, boardId, boardArticleCount));
            newestFirst.add(0, articleId);
        }
        return newestFirst;
    }

    private Event<ArticleCreatedEventPayload> created(long articleId, long boardId, long boardArticleCount) {
        return event(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(articleId, boardId, 7L, "이벤트 제목",
                "본문", WRITTEN_AT, WRITTEN_AT, boardArticleCount));
    }

    private <T extends EventPayload> Event<T> event(EventType type, T payload) {
        return Event.of(EVENT_SEQUENCE.incrementAndGet(), type, WRITTEN_AT, payload);
    }

    private void handle(Event<? extends EventPayload> event) {
        articleReadEventHandler.handle(event);
    }

    private String articlePath(long boardId, long articleId) {
        return "/v1/boards/" + boardId + "/articles/" + articleId;
    }

    private ArticleReadDetailResponse read(long boardId, long articleId, Long userId) throws Exception {
        var request = get(articlePath(boardId, articleId));
        if (userId != null) {
            request.param("userId", String.valueOf(userId));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleReadDetailResponse.class);
    }

    private ArticleReadPageResponse readPage(long boardId, long page, long pageSize) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/boards/" + boardId + "/articles")
                        .param("page", String.valueOf(page))
                        .param("pageSize", String.valueOf(pageSize)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleReadPageResponse.class);
    }

    private List<ArticleReadResponse> readScroll(long boardId, long pageSize, long lastArticleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/boards/" + boardId + "/articles/infinite-scroll")
                        .param("pageSize", String.valueOf(pageSize))
                        .param("lastArticleId", String.valueOf(lastArticleId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private <V> List<V> runConcurrently(List<Callable<V>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<V>> running = new ArrayList<>();
        for (Callable<V> action : actions) {
            running.add(executor.submit(() -> {
                startSignal.await();
                return action.call();
            }));
        }
        startSignal.countDown();
        List<V> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<V> action : running) {
            try {
                results.add(action.get(60, TimeUnit.SECONDS));
            } catch (ExecutionException failure) {
                failures.add(failure.getCause());
            }
        }
        executor.shutdown();
        assertThat(failures).isEmpty();
        return results;
    }
}
