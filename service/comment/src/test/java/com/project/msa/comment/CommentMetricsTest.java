package com.project.msa.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.comment.CommentDtos.CommentCreateRequest;
import com.project.msa.comment.CommentDtos.CommentResponse;
import com.project.msa.common.monitoring.Eventually;
import com.project.msa.common.monitoring.PrometheusScrape;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(CommentTestConfig.class)
class CommentMetricsTest {

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Map<String, String> AFTER_COMMIT_SUCCESS =
            Map.of("path", "after_commit", "result", "success");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommentService commentService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("HTTP 응답 시간 히스토그램 버킷이 나오고, 댓글을 쓰면 커밋 직후 발행 성공이 오른 뒤 outbox가 비워진다")
    void exposesHttpHistogramAndOutboxPublish() throws Exception {
        double publishedBefore = scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS);

        commentService.write(90_001L, new CommentCreateRequest(1L, "댓글", null));
        mockMvc.perform(get("/v1/articles/90001/comments").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());

        assertThat(scrape().has("http_server_requests_seconds_bucket", Map.of("application", "comment"))).isTrue();
        Eventually.until(() -> scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS) > publishedBefore,
                SETTLE_TIMEOUT);
        Eventually.until(() -> scrape().value("outbox_pending") == 0.0, SETTLE_TIMEOUT);
    }

    @Test
    @DisplayName("같은 경로를 다른 트랜잭션이 먼저 잡고 커밋하면 경로를 다시 계산하고 재시도 횟수가 오른다")
    void countsPathConflictRetry() throws Exception {
        long articleId = 90_002L;
        CommentResponse parent = commentService.write(articleId, new CommentCreateRequest(1L, "부모", null));
        double retriesBefore = scrape().value("comment_path_conflict_retry_total");
        CountDownLatch rowHeld = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);

        // 서비스가 계산할 첫 자식 경로를 다른 트랜잭션이 먼저 넣고 커밋하지 않은 채 쥔다
        CompletableFuture<Void> competingWriter = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    JdbcClient.create(dataSource).sql("""
                                    INSERT INTO comment (comment_id, article_id, writer_id, content, path, deleted, created_at)
                                    VALUES (1, :articleId, 2, '먼저 온 답글', :path, false, NOW())
                                    """)
                            .param("articleId", articleId)
                            .param("path", parent.path() + "00000")
                            .update();
                    rowHeld.countDown();
                    await(releaseRow);
                }));
        assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CommentResponse> reply = CompletableFuture.supplyAsync(() ->
                commentService.write(articleId, new CommentCreateRequest(1L, "답글", parent.commentId())));
        // 서비스의 INSERT가 같은 유니크 키에서 행 락을 기다리는 것을 확인한 뒤 먼저 온 트랜잭션을 커밋한다
        Eventually.until(this::someTransactionWaitsForLock, SETTLE_TIMEOUT);
        releaseRow.countDown();
        competingWriter.get(10, TimeUnit.SECONDS);

        assertThat(reply.get(10, TimeUnit.SECONDS).path()).isEqualTo(parent.path() + "00001");
        assertThat(scrape().value("comment_path_conflict_retry_total")).isEqualTo(retriesBefore + 1);
    }

    /**
     * 잡힌 행과 같은 유니크 키로 들어가는 서비스의 INSERT는 락이 풀리기 전에는 끝날 수 없다.
     * 그 문장이 실행 중으로 보이면 락을 기다리는 것이다. (테스트 DB 사용자는 PROCESS 권한이 없어 자기 연결만 본다)
     */
    private boolean someTransactionWaitsForLock() {
        return JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO LIKE 'insert into comment%'")
                .query(Long.class)
                .single() > 0;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private PrometheusScrape scrape() throws Exception {
        return PrometheusScrape.of(mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
