package com.project.msa.articleread;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArticleReadProperties.class)
class ArticleReadConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    ArticleOriginClient articleOriginClient(RestClient.Builder restClientBuilder, ArticleReadProperties properties) {
        return new ArticleOriginClient(restClientBuilder.clone().baseUrl(properties.origin().articleUrl()).build());
    }

    @Bean
    ArticleCountOriginClient articleCountOriginClient(RestClient.Builder restClientBuilder,
                                                      ArticleReadProperties properties) {
        return new ArticleCountOriginClient(
                restClientBuilder.clone().baseUrl(properties.origin().commentUrl()).build(),
                restClientBuilder.clone().baseUrl(properties.origin().likeUrl()).build());
    }

    @Bean
    ViewClient viewClient(RestClient.Builder restClientBuilder, ArticleReadProperties properties) {
        return new ViewClient(restClientBuilder.clone().baseUrl(properties.origin().viewUrl()).build());
    }
}
