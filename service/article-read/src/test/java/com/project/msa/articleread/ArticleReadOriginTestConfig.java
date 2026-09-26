package com.project.msa.articleread;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class ArticleReadOriginTestConfig {

    @Bean
    @Primary
    MovableClock movableClock() {
        return new MovableClock(Instant.parse("2026-09-26T10:00:00Z"));
    }

    /** 논리 만료(10분) 앞뒤를 보려고 테스트가 시계를 앞으로 옮긴다. */
    static class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
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
