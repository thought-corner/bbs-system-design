package com.project.msa.common.outbox;

import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
})
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayAutoConfiguration {

    @Bean
    OutboxRepository outboxRepository(DataSource dataSource) {
        return new OutboxRepository(JdbcClient.create(dataSource));
    }

    @Bean
    OutboxEventPublisher outboxEventPublisher(OutboxRepository outboxRepository,
                                              ApplicationEventPublisher applicationEventPublisher,
                                              Snowflake snowflake, ObjectProvider<Clock> clock) {
        return new OutboxEventPublisher(outboxRepository, applicationEventPublisher, snowflake,
                clock.getIfAvailable(Clock::systemDefaultZone));
    }

    @Bean
    MessageRelay messageRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                              PlatformTransactionManager transactionManager, OutboxRelayProperties properties,
                              ObjectProvider<Clock> clock) {
        return new MessageRelay(outboxRepository, kafkaTemplate, new TransactionTemplate(transactionManager),
                properties, clock.getIfAvailable(Clock::systemDefaultZone));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "outbox.relay.polling-enabled", havingValue = "true", matchIfMissing = true)
    static class OutboxPollingConfiguration {

        @Bean
        OutboxPollingScheduler outboxPollingScheduler(MessageRelay messageRelay) {
            return new OutboxPollingScheduler(messageRelay);
        }
    }
}
