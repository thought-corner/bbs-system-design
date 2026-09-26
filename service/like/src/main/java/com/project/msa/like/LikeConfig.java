package com.project.msa.like;

import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LikeConfig {

    @Bean
    Snowflake snowflake(@Value("${snowflake.node-id}") long nodeId) {
        return new Snowflake(nodeId);
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
