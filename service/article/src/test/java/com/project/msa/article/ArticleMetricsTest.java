package com.project.msa.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.article.ArticleDtos.ArticleCreateRequest;
import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.monitoring.Eventually;
import com.project.msa.common.monitoring.PrometheusScrape;
import com.project.msa.common.outbox.MessageRelay;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
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

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(ArticleTestConfig.class)
class ArticleMetricsTest {

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Map<String, String> AFTER_COMMIT_SUCCESS =
            Map.of("path", "after_commit", "result", "success");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private MessageRelay messageRelay;

    @Autowired
    private Snowflake snowflake;

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("HTTP 요청 응답 시간이 p95·p99를 계산할 수 있는 히스토그램 버킷으로 나온다")
    void exposesHttpLatencyHistogram() throws Exception {
        mockMvc.perform(get("/v1/boards/1/articles").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());

        assertThat(scrape().has("http_server_requests_seconds_bucket", Map.of("application", "article"))).isTrue();
    }

    @Test
    @DisplayName("게시글을 쓰면 커밋 직후 발행 성공이 오르고, 발행이 끝나면 남은 outbox 행이 0으로 돌아온다")
    void countsAfterCommitPublishAndDrainsOutbox() throws Exception {
        double publishedBefore = scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS);

        articleService.write(90_001L, new ArticleCreateRequest(7L, "지표", "본문"));

        awaitUntil(() -> scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS) > publishedBefore);
        awaitUntil(() -> scrape().value("outbox_pending") == 0.0);
        assertThat(scrape().value("outbox_oldest_age_seconds")).isZero();
    }

    @Test
    @DisplayName("오래 남은 outbox 행을 폴링이 재발행하면 폴링 발행 성공이 오른다")
    void countsPollingPublish() throws Exception {
        Map<String, String> pollingSuccess = Map.of("path", "polling", "result", "success");
        double publishedBefore = scrape().value("outbox_publish_total", pollingSuccess);
        insertStaleOutbox();
        assertThat(scrape().value("outbox_pending")).isGreaterThanOrEqualTo(1.0);
        assertThat(scrape().value("outbox_oldest_age_seconds")).isGreaterThanOrEqualTo(60.0);

        messageRelay.publishPending();

        assertThat(scrape().value("outbox_publish_total", pollingSuccess)).isGreaterThan(publishedBefore);
    }

    private void insertStaleOutbox() {
        LocalDateTime strandedAt = LocalDateTime.now(clock).minusMinutes(1);
        long articleId = snowflake.nextId();
        Event<ArticleCreatedEventPayload> event = Event.of(snowflake.nextId(), EventType.ARTICLE_CREATED, strandedAt,
                new ArticleCreatedEventPayload(articleId, 90_002L, 7L, "남은 글", "본문", strandedAt, strandedAt, 1L));
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO outbox (outbox_id, event_type, payload, partition_key, created_at)
                        VALUES (:outboxId, :eventType, :payload, :partitionKey, :createdAt)
                        """)
                .param("outboxId", snowflake.nextId())
                .param("eventType", EventType.ARTICLE_CREATED.name())
                .param("payload", event.toJson())
                .param("partitionKey", articleId)
                .param("createdAt", strandedAt)
                .update();
    }

    private PrometheusScrape scrape() throws Exception {
        return PrometheusScrape.of(mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void awaitUntil(Eventually.Condition condition) throws Exception {
        Eventually.until(condition, SETTLE_TIMEOUT);
    }
}
