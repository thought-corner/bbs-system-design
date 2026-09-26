package com.project.msa.hotarticle;

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
}
