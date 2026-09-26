package com.project.msa.articleread;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
class ArticleReadTestConfig {

    /** 원본 클라이언트가 이 빌더를 복제하므로, 원본 호출은 모두 아래 가짜 서버로 간다. */
    private final RestClient.Builder originRestClientBuilder = RestClient.builder();
    private final MockRestServiceServer originServer =
            MockRestServiceServer.bindTo(originRestClientBuilder).ignoreExpectOrder(true).build();

    @Bean
    @Primary
    RestClient.Builder originRestClientBuilder() {
        return originRestClientBuilder;
    }

    @Bean
    MockRestServiceServer originServer() {
        return originServer;
    }

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
