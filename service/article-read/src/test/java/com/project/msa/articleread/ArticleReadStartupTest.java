package com.project.msa.articleread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 원본 호출용 빌더를 테스트가 넣어 주지 않아도 앱이 뜨는지 본다.
 * 다른 테스트는 가짜 원본 서버에 묶인 빌더를 넣어 주므로, 운영에서 빌더 빈이 없어 기동에 실패해도 드러나지 않는다.
 */
@SpringBootTest
@Import(ArticleReadStartupTest.InfraOnly.class)
class ArticleReadStartupTest {

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ArticleReadService articleReadService;

    @Test
    @DisplayName("운영 설정만으로 원본 호출용 RestClient 빌더가 자동 구성되어 앱이 뜬다")
    void startsWithAutoConfiguredRestClientBuilder() {
        assertThat(restClientBuilder).isNotNull();
        assertThat(articleReadService).isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InfraOnly {

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka-native:3.8.0");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>("redis:7.4").withExposedPorts(6379);
        }
    }
}
