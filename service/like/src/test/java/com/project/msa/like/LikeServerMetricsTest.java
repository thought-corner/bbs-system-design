package com.project.msa.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.monitoring.Eventually;
import com.project.msa.common.monitoring.PrometheusScrape;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * MockMvc는 Tomcat을 띄우지 않아 Tomcat 스레드 지표를 볼 수 없다. 실제 포트로 띄워 공통 지표가 모두 나오는지 본다.
 * 서비스 6개가 같은 `common/monitoring` 배선을 쓰므로 대표로 like 하나에서 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@Import(LikeTestConfig.class)
class LikeServerMetricsTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("실제 서버에서 Tomcat 스레드·JVM·Hikari·Kafka 프로듀서 공통 지표가 나온다")
    void exposesCommonServerMetrics() throws Exception {
        RestClient restClient = RestClient.create("http://localhost:" + port);
        restClient.post().uri("/v1/articles/90001/likes/users/1").retrieve().toBodilessEntity();
        Map<String, String> like = Map.of("application", "like");

        Eventually.until(() -> scrape(restClient).has("kafka_producer_record_send_total", like), Duration.ofSeconds(20));
        PrometheusScrape scrape = scrape(restClient);

        assertThat(scrape.has("tomcat_threads_busy_threads", like)).isTrue();
        assertThat(scrape.has("tomcat_threads_config_max_threads", like)).isTrue();
        assertThat(scrape.has("jvm_memory_used_bytes", like)).isTrue();
        assertThat(scrape.has("jvm_threads_live_threads", like)).isTrue();
        assertThat(scrape.has("hikaricp_connections_active", like)).isTrue();
        assertThat(scrape.has("hikaricp_connections_pending", like)).isTrue();
        assertThat(scrape.has("http_server_requests_seconds_bucket", like)).isTrue();
    }

    private PrometheusScrape scrape(RestClient restClient) {
        return PrometheusScrape.of(restClient.get().uri("/actuator/prometheus").retrieve().body(String.class));
    }
}
