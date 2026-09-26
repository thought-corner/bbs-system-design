package com.project.msa.articleread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.monitoring.PrometheusScrape;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(ArticleReadTestConfig.class)
class ArticleReadMetricsTest {

    private static final long BOARD_ID = 70_001L;
    private static final LocalDateTime WRITTEN_AT = LocalDateTime.of(2026, 9, 27, 10, 0);
    private static final Map<String, String> LIKED = Map.of("type", "ARTICLE_LIKED");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ArticleReadEventHandler articleReadEventHandler;

    @Autowired
    private MockRestServiceServer originServer;

    @BeforeEach
    void setUp() {
        originServer.reset();
    }

    @Test
    @DisplayName("HTTP 히스토그램이 나오고, 읽기 모델에 있는 게시글 상세는 read_model로 센다")
    void countsReadModelSource() throws Exception {
        long articleId = 4_000_000_000_000_000_001L;
        articleReadEventHandler.handle(created(articleId));
        double readModelBefore = scrape().value("article_read_source_total", Map.of("source", "read_model"));

        mockMvc.perform(get("/v1/boards/" + BOARD_ID + "/articles/" + articleId)).andExpect(status().isOk());

        PrometheusScrape scrape = scrape();
        assertThat(scrape.has("http_server_requests_seconds_bucket", Map.of("application", "article-read"))).isTrue();
        assertThat(scrape.value("article_read_source_total", Map.of("source", "read_model")))
                .isEqualTo(readModelBefore + 1);
    }

    @Test
    @DisplayName("읽기 모델에 없는 게시글을 원본으로 채우면 origin_refresh와 갱신 락 획득을 센다")
    void countsOriginRefreshAndLock() throws Exception {
        long articleId = 4_000_000_000_000_000_002L;
        originServer.expect(once(), requestTo("http://localhost:8081/v1/boards/" + BOARD_ID + "/articles/" + articleId))
                .andRespond(withSuccess(objectMapper.writeValueAsString(
                        new ArticleBody(articleId, BOARD_ID, 7L, "원본", "본문", WRITTEN_AT, WRITTEN_AT)),
                        MediaType.APPLICATION_JSON));
        originServer.expect(once(), requestTo("http://localhost:8082/v1/articles/" + articleId + "/comments/count"))
                .andRespond(withSuccess("{\"articleId\":" + articleId + ",\"commentCount\":0}", MediaType.APPLICATION_JSON));
        originServer.expect(once(), requestTo("http://localhost:8083/v1/articles/" + articleId + "/likes/count"))
                .andRespond(withSuccess("{\"articleId\":" + articleId + ",\"likeCount\":0}", MediaType.APPLICATION_JSON));
        double refreshBefore = scrape().value("article_read_source_total", Map.of("source", "origin_refresh"));
        double lockBefore = scrape().value("article_read_refresh_lock_total", Map.of("acquired", "true"));

        mockMvc.perform(get("/v1/boards/" + BOARD_ID + "/articles/" + articleId)).andExpect(status().isOk());

        originServer.verify();
        assertThat(scrape().value("article_read_source_total", Map.of("source", "origin_refresh")))
                .isEqualTo(refreshBefore + 1);
        assertThat(scrape().value("article_read_refresh_lock_total", Map.of("acquired", "true")))
                .isEqualTo(lockBefore + 1);
    }

    @Test
    @DisplayName("이벤트를 반영하면 반영 지연 타이머가 오르고, 같은 이벤트를 다시 받으면 버린 이벤트 수가 오른다")
    void countsConsumeLagAndStaleEvents() throws Exception {
        long articleId = 4_000_000_000_000_000_003L;
        articleReadEventHandler.handle(created(articleId));
        Event<ArticleLikedEventPayload> liked = Event.of(20L, EventType.ARTICLE_LIKED, WRITTEN_AT,
                new ArticleLikedEventPayload(articleId, 1L, 1L));
        double lagCountBefore = scrape().value("event_consume_lag_seconds_count", LIKED);
        double staleBefore = scrape().value("event_consume_stale_total", LIKED);

        articleReadEventHandler.handle(liked);
        double staleAfterFirst = scrape().value("event_consume_stale_total", LIKED);
        articleReadEventHandler.handle(liked);

        assertThat(scrape().value("event_consume_lag_seconds_count", LIKED)).isEqualTo(lagCountBefore + 2);
        assertThat(staleAfterFirst).isEqualTo(staleBefore);
        assertThat(scrape().value("event_consume_stale_total", LIKED)).isEqualTo(staleBefore + 1);
    }

    private Event<ArticleCreatedEventPayload> created(long articleId) {
        return Event.of(10L, EventType.ARTICLE_CREATED, WRITTEN_AT,
                new ArticleCreatedEventPayload(articleId, BOARD_ID, 7L, "제목", "본문", WRITTEN_AT, WRITTEN_AT, 1L));
    }

    private PrometheusScrape scrape() throws Exception {
        return PrometheusScrape.of(mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
