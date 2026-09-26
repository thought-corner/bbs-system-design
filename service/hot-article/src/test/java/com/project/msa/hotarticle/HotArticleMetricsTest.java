package com.project.msa.hotarticle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.monitoring.PrometheusScrape;
import java.time.LocalDateTime;
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
@Import(HotArticleTestConfig.class)
class HotArticleMetricsTest {

    private static final Map<String, String> LIKED = Map.of("type", "ARTICLE_LIKED");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2028, 5, 1, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HotArticleEventHandler hotArticleEventHandler;

    @Test
    @DisplayName("HTTP 응답 시간 히스토그램 버킷이 나온다")
    void exposesHttpLatencyHistogram() throws Exception {
        mockMvc.perform(get("/v1/hot-articles").param("date", "20280501")).andExpect(status().isOk());

        assertThat(scrape().has("http_server_requests_seconds_bucket", Map.of("application", "hot-article"))).isTrue();
    }

    @Test
    @DisplayName("이벤트를 반영하면 반영 지연 타이머가 오르고, 같은 이벤트를 다시 받으면 버린 이벤트 수가 오른다")
    void countsConsumeLagAndStaleEvents() throws Exception {
        long articleId = 80_001L;
        hotArticleEventHandler.handle(Event.of(1L, EventType.ARTICLE_CREATED, CREATED_AT,
                new ArticleCreatedEventPayload(articleId, 1L, 7L, "제목", "본문", CREATED_AT, CREATED_AT, 1L)));
        Event<ArticleLikedEventPayload> liked = Event.of(2L, EventType.ARTICLE_LIKED, CREATED_AT,
                new ArticleLikedEventPayload(articleId, 1L, 1L));
        double lagCountBefore = scrape().value("event_consume_lag_seconds_count", LIKED);
        double staleBefore = scrape().value("event_consume_stale_total", LIKED);

        hotArticleEventHandler.handle(liked);
        double staleAfterFirst = scrape().value("event_consume_stale_total", LIKED);
        hotArticleEventHandler.handle(liked);

        assertThat(scrape().value("event_consume_lag_seconds_count", LIKED)).isEqualTo(lagCountBefore + 2);
        assertThat(staleAfterFirst).isEqualTo(staleBefore);
        assertThat(scrape().value("event_consume_stale_total", LIKED)).isEqualTo(staleBefore + 1);
    }

    private PrometheusScrape scrape() throws Exception {
        return PrometheusScrape.of(mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
