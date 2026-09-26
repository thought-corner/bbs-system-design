package com.project.msa.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.view.ViewController.ArticleViewCountResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ViewTestConfig.class)
class ViewApiTest {

    /** 테스트마다 다른 게시글을 써서 조회수·어뷰징 키·백업이 섞이지 않게 한다. */
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(1_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ViewService viewService;

    @Autowired
    private ViewCountBackupService viewCountBackupService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient = JdbcClient.create(dataSource);
    }

    @Test
    @DisplayName("첫 조회는 조회수 1을 돌려준다")
    void firstViewReturnsOne() throws Exception {
        long articleId = newArticle();

        assertThat(view(articleId, 1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자가 10분 안에 다시 조회하면 증가하지 않고 현재 조회수를 돌려준다")
    void repeatedViewWithinWindowDoesNotIncrease() throws Exception {
        long articleId = newArticle();
        view(articleId, 1L);
        view(articleId, 2L);

        long repeatedView = view(articleId, 1L);

        assertThat(repeatedView).isEqualTo(2);
        assertThat(count(articleId)).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 사용자가 조회하면 조회수가 오른다")
    void viewFromAnotherUserIncreases() throws Exception {
        long articleId = newArticle();
        view(articleId, 1L);

        assertThat(view(articleId, 2L)).isEqualTo(2);
        assertThat(view(articleId, 3L)).isEqualTo(3);
    }

    @Test
    @DisplayName("어뷰징 키의 TTL은 600초이고, 키가 만료되면 같은 사용자의 조회가 다시 오른다")
    void abuseLockExpiresAfterTenMinutes() throws Exception {
        long articleId = newArticle();
        view(articleId, 1L);
        String abuseLockKey = ArticleViewCountRedisRepository.abuseLockKey(articleId, 1L);

        Long ttlSeconds = redisTemplate.getExpire(abuseLockKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(590L, 600L);

        redisTemplate.delete(abuseLockKey);

        assertThat(view(articleId, 1L)).isEqualTo(2);
    }

    @Test
    @DisplayName("100번째 조회에서 MySQL 백업이 100이 되고 99번째까지는 백업이 없다")
    void backsUpEveryHundredViews() throws Exception {
        long articleId = newArticle();
        for (long userId = 1; userId <= 99; userId++) {
            viewService.increase(articleId, userId);
        }
        assertThat(backupViewCount(articleId)).isEmpty();

        viewService.increase(articleId, 100L);

        assertThat(backupViewCount(articleId)).contains(100L);
    }

    @Test
    @DisplayName("백업은 더 작은 값으로 덮어쓰지 않는다")
    void backupNeverMovesBackwards() {
        long articleId = newArticle();
        viewCountBackupService.backup(articleId, 200L);

        viewCountBackupService.backup(articleId, 100L);

        assertThat(backupViewCount(articleId)).contains(200L);
    }

    @Test
    @DisplayName("Redis 조회수 키가 사라지면 MySQL 백업값에서 이어 오른다")
    void restoresFromBackupWhenRedisKeyIsLost() throws Exception {
        long articleId = newArticle();
        for (long userId = 1; userId <= 100; userId++) {
            viewService.increase(articleId, userId);
        }
        redisTemplate.delete(ArticleViewCountRedisRepository.viewCountKey(articleId));

        assertThat(view(articleId, 101L)).isEqualTo(101);
    }

    @Test
    @DisplayName("조회수 조회는 Redis 값을, Redis 키가 없으면 백업값을, 둘 다 없으면 0을 돌려준다")
    void countFallsBackToBackup() throws Exception {
        long articleId = newArticle();
        long untouchedArticleId = newArticle();
        for (long userId = 1; userId <= 102; userId++) {
            viewService.increase(articleId, userId);
        }
        assertThat(count(articleId)).isEqualTo(102);

        redisTemplate.delete(ArticleViewCountRedisRepository.viewCountKey(articleId));

        assertThat(count(articleId)).isEqualTo(100);
        assertThat(count(untouchedArticleId)).isZero();
    }

    @Test
    @DisplayName("서로 다른 사용자 여러 명이 동시에 조회하면 조회수가 정확히 사용자 수다")
    void concurrentViewsFromDifferentUsersAreCounted() throws Exception {
        long articleId = newArticle();
        int userCount = 50;

        List<Callable<Long>> views = new ArrayList<>();
        for (long userId = 1; userId <= userCount; userId++) {
            long viewingUserId = userId;
            views.add(() -> viewService.increase(articleId, viewingUserId));
        }
        runConcurrently(views);

        assertThat(count(articleId)).isEqualTo(userCount);
    }

    @Test
    @DisplayName("같은 사용자가 동시에 여러 번 조회해도 조회수는 1이다")
    void concurrentViewsFromSameUserCountOnce() throws Exception {
        long articleId = newArticle();

        List<Callable<Long>> views = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            views.add(() -> viewService.increase(articleId, 7L));
        }
        runConcurrently(views);

        assertThat(count(articleId)).isEqualTo(1);
    }

    private long newArticle() {
        return ARTICLE_SEQUENCE.incrementAndGet();
    }

    private long view(long articleId, long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/articles/" + articleId + "/views/users/" + userId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleViewCountResponse.class)
                .viewCount();
    }

    private long count(long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/articles/" + articleId + "/views/count"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleViewCountResponse.class)
                .viewCount();
    }

    private Optional<Long> backupViewCount(long articleId) {
        return jdbcClient.sql("SELECT view_count FROM article_view_count WHERE article_id = :articleId")
                .param("articleId", articleId)
                .query(Long.class)
                .optional();
    }

    private void runConcurrently(List<Callable<Long>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Long>> running = new ArrayList<>();
        for (Callable<Long> action : actions) {
            running.add(executor.submit(() -> {
                startSignal.await();
                return action.call();
            }));
        }
        startSignal.countDown();
        List<Throwable> failures = new ArrayList<>();
        for (Future<Long> action : running) {
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
