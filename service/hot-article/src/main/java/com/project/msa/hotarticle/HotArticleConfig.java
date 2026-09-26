package com.project.msa.hotarticle;

import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(HotArticleProperties.class)
class HotArticleConfig {

    @Bean
    Clock clock(HotArticleProperties properties) {
        return Clock.system(properties.zone());
    }

    /** 반영 지연은 업무 시계가 아니라 실제 시스템 시계로 잰다. */
    @Bean
    EventConsumeMetrics eventConsumeMetrics(MeterRegistry meterRegistry) {
        return new EventConsumeMetrics(meterRegistry, Clock.systemDefaultZone());
    }
}
