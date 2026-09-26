package com.project.msa.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(ViewTestConfig.class)
class ViewEventTest {

    private static final long ARTICLE_ID = 50_001L;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(20);
    /** 오지 않아야 하는 이벤트를 확인할 때 기다리는 창. 커밋 직후 발행은 수십 ms 안에 도착한다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofSeconds(3);

    @Autowired
    private ViewService viewService;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Test
    @DisplayName("100번째 조회에서 board-view 토픽에 articleId 키로 조회수 100인 ArticleViewed 이벤트가 오고, 그 전에는 없다")
    void viewedEventArrivesOnlyAtBackup() {
        for (long userId = 1; userId <= 99; userId++) {
            viewService.increase(ARTICLE_ID, userId);
        }
        assertThat(eventsFor(ABSENCE_WINDOW)).isEmpty();

        viewService.increase(ARTICLE_ID, 100L);

        List<ConsumerRecord<String, String>> records = awaitEvents();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(String.valueOf(ARTICLE_ID));
        Event<EventPayload> viewed = Event.fromJson(records.get(0).value());
        assertThat(viewed.type()).isEqualTo(EventType.ARTICLE_VIEWED);
        assertThat(viewed.payload()).isEqualTo(new ArticleViewedEventPayload(ARTICLE_ID, 100L));
    }

    private List<ConsumerRecord<String, String>> awaitEvents() {
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            List<ConsumerRecord<String, String>> records = eventsFor(ABSENCE_WINDOW);
            if (!records.isEmpty()) {
                return records;
            }
        }
        throw new AssertionError("ArticleViewed for article " + ARTICLE_ID + " did not arrive within " + ARRIVAL_TIMEOUT);
    }

    private List<ConsumerRecord<String, String>> eventsFor(Duration window) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = boardViewConsumer()) {
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(String.valueOf(ARTICLE_ID))) {
                        collected.add(record);
                    }
                }
            }
        }
        return collected;
    }

    private KafkaConsumer<String, String> boardViewConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "view-event-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(EventType.Topic.BOARD_VIEW));
        return consumer;
    }
}
