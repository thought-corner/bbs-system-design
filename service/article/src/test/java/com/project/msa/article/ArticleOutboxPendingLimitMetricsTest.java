package com.project.msa.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.monitoring.PrometheusScrape;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.LocalDateTime;
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

/** 적체가 커도 `outbox_pending` 스크랩 비용이 상한에 묶이는지 본다. 상한을 작게(3) 두어 확인한다. */
@SpringBootTest(properties = "outbox.relay.pending-count-limit=3")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(ArticleTestConfig.class)
class ArticleOutboxPendingLimitMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Snowflake snowflake;

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("outbox 행이 상한보다 많으면 outbox_pending은 상한에서 멈춘다")
    void pendingCountStopsAtLimit() throws Exception {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        for (int i = 0; i < 5; i++) {
            insertStaleOutbox(jdbcClient);
        }

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox").query(Long.class).single()).isGreaterThanOrEqualTo(5);
        assertThat(scrape().value("outbox_pending")).isEqualTo(3.0);
    }

    private void insertStaleOutbox(JdbcClient jdbcClient) {
        LocalDateTime strandedAt = LocalDateTime.now(clock).minusMinutes(1);
        long articleId = snowflake.nextId();
        Event<ArticleCreatedEventPayload> event = Event.of(snowflake.nextId(), EventType.ARTICLE_CREATED, strandedAt,
                new ArticleCreatedEventPayload(articleId, 90_003L, 7L, "남은 글", "본문", strandedAt, strandedAt, 1L));
        jdbcClient.sql("""
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
}
