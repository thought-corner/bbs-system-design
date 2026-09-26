package com.project.msa.hotarticle;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
class HotArticleTestConfig {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

    @Bean
    @Primary
    MovableClock movableClock() {
        return new MovableClock(LocalDateTime.of(2026, 9, 27, 9, 0));
    }

    /** 확정 시각(01시) 앞뒤의 날짜 규칙을 보려고 테스트가 시계를 옮긴다. */
    static class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(LocalDateTime now) {
            moveTo(now);
        }

        void moveTo(LocalDateTime now) {
            this.now = now.atZone(SEOUL).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
