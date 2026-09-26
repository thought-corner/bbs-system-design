package com.project.msa.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.common.monitoring.Eventually;
import com.project.msa.common.monitoring.PrometheusScrape;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(LikeTestConfig.class)
class LikeMetricsTest {

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Map<String, String> AFTER_COMMIT_SUCCESS =
            Map.of("path", "after_commit", "result", "success");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("HTTP 응답 시간 히스토그램 버킷이 나오고, 좋아요하면 커밋 직후 발행 성공이 오른 뒤 outbox가 비워진다")
    void exposesHttpHistogramAndOutboxPublish() throws Exception {
        double publishedBefore = scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS);

        mockMvc.perform(post("/v1/articles/90001/likes/users/1")).andExpect(status().isOk());

        assertThat(scrape().has("http_server_requests_seconds_bucket", Map.of("application", "like"))).isTrue();
        Eventually.until(() -> scrape().value("outbox_publish_total", AFTER_COMMIT_SUCCESS) > publishedBefore,
                SETTLE_TIMEOUT);
        Eventually.until(() -> scrape().value("outbox_pending") == 0.0, SETTLE_TIMEOUT);
    }

    private PrometheusScrape scrape() throws Exception {
        return PrometheusScrape.of(mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
