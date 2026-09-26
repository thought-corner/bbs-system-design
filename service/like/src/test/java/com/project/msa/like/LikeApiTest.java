package com.project.msa.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.like.LikeController.ArticleLikeCountResponse;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(LikeTestConfig.class)
class LikeApiTest {

    /** 테스트마다 다른 게시글을 써서 좋아요 수가 섞이지 않게 한다. */
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(1_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LikeService likeService;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient = JdbcClient.create(dataSource);
    }

    @Test
    @DisplayName("좋아요하면 좋아요 수가 1이 된다")
    void likeIncreasesCount() throws Exception {
        long articleId = newArticle();

        like(articleId, 1L);

        assertThat(likeCount(articleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자가 다시 좋아요해도 성공 응답이고 좋아요 수는 그대로다")
    void duplicateLikeKeepsCount() throws Exception {
        long articleId = newArticle();
        like(articleId, 1L);

        like(articleId, 1L);

        assertThat(likeCount(articleId)).isEqualTo(1);
        assertThat(likeRows(articleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요를 취소하면 좋아요 수가 1 줄어든다")
    void unlikeDecreasesCount() throws Exception {
        long articleId = newArticle();
        like(articleId, 1L);
        like(articleId, 2L);

        unlike(articleId, 1L);

        assertThat(likeCount(articleId)).isEqualTo(1);
        assertThat(likeRows(articleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요하지 않은 사용자가 취소해도 성공 응답이고 좋아요 수는 음수가 되지 않는다")
    void unlikeWithoutLikeKeepsCount() throws Exception {
        long articleId = newArticle();
        like(articleId, 1L);
        unlike(articleId, 1L);

        unlike(articleId, 1L);
        unlike(articleId, 2L);

        assertThat(likeCount(articleId)).isZero();
    }

    @Test
    @DisplayName("좋아요가 없는 게시글의 좋아요 수는 0이고 다른 게시글의 좋아요는 섞이지 않는다")
    void countsAreIsolatedPerArticle() throws Exception {
        long articleId = newArticle();
        long otherArticleId = newArticle();
        long untouchedArticleId = newArticle();
        like(articleId, 1L);
        like(otherArticleId, 1L);
        like(otherArticleId, 2L);

        assertThat(likeCount(articleId)).isEqualTo(1);
        assertThat(likeCount(otherArticleId)).isEqualTo(2);
        assertThat(likeCount(untouchedArticleId)).isZero();
    }

    @Test
    @DisplayName("서로 다른 사용자 여러 명이 동시에 좋아요하면 좋아요 수가 정확히 사용자 수다")
    void concurrentLikesFromDifferentUsersAreCounted() throws Exception {
        long articleId = newArticle();
        int userCount = 30;

        List<Callable<Void>> likes = new ArrayList<>();
        for (long userId = 1; userId <= userCount; userId++) {
            long likingUserId = userId;
            likes.add(() -> {
                likeService.like(articleId, likingUserId);
                return null;
            });
        }
        runConcurrently(likes);

        assertThat(likeCount(articleId)).isEqualTo(userCount);
        assertThat(likeRows(articleId)).isEqualTo(userCount);
    }

    @Test
    @DisplayName("같은 사용자가 동시에 여러 번 좋아요해도 좋아요 행은 하나이고 좋아요 수는 1이다")
    void concurrentLikesFromSameUserCountOnce() throws Exception {
        long articleId = newArticle();

        List<Callable<Void>> likes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            likes.add(() -> {
                likeService.like(articleId, 7L);
                return null;
            });
        }
        runConcurrently(likes);

        assertThat(likeRows(articleId)).isEqualTo(1);
        assertThat(likeCount(articleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요와 취소가 동시에 섞여도 최종 좋아요 수는 남은 좋아요 행 수와 같다")
    void concurrentLikesAndUnlikesStayConsistent() throws Exception {
        long articleId = newArticle();
        for (long userId = 1; userId <= 15; userId++) {
            likeService.like(articleId, userId);
        }

        List<Callable<Void>> mixed = new ArrayList<>();
        for (long userId = 1; userId <= 30; userId++) {
            long actingUserId = userId;
            mixed.add(() -> {
                if (actingUserId % 2 == 0) {
                    likeService.unlike(articleId, actingUserId);
                } else {
                    likeService.like(articleId, actingUserId);
                }
                return null;
            });
            mixed.add(() -> {
                likeService.like(articleId, actingUserId);
                return null;
            });
        }
        runConcurrently(mixed);

        assertThat(likeCount(articleId)).isEqualTo(likeRows(articleId));
        assertThat(likeRows(articleId)).isBetween(15L, 30L);
    }

    private long newArticle() {
        return ARTICLE_SEQUENCE.incrementAndGet();
    }

    private void like(long articleId, long userId) throws Exception {
        mockMvc.perform(post("/v1/articles/" + articleId + "/likes/users/" + userId)).andExpect(status().isOk());
    }

    private void unlike(long articleId, long userId) throws Exception {
        mockMvc.perform(delete("/v1/articles/" + articleId + "/likes/users/" + userId)).andExpect(status().isOk());
    }

    private long likeCount(long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/articles/" + articleId + "/likes/count"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleLikeCountResponse.class)
                .likeCount();
    }

    private long likeRows(long articleId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM article_like WHERE article_id = :articleId")
                .param("articleId", articleId)
                .query(Long.class)
                .single();
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
