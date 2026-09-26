package com.project.msa.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.comment.CommentDtos.CommentCreateRequest;
import com.project.msa.comment.CommentDtos.CommentResponse;
import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
@Import(CommentTestConfig.class)
class CommentEventTest {

    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(50_000);
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private CommentService commentService;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Test
    @DisplayName("댓글을 생성하면 board-comment 토픽에 articleId 키로 누적 댓글 수를 담은 CommentCreated 이벤트가 도착한다")
    void createdEventArrivesWithCumulativeCount() {
        long articleId = ARTICLE_SEQUENCE.incrementAndGet();
        CommentResponse parent = write(articleId, null);

        CommentResponse reply = write(articleId, parent.commentId());

        ConsumerRecord<String, String> record = awaitEvent(articleId, EventType.COMMENT_CREATED, reply.commentId());
        assertThat(record.key()).isEqualTo(String.valueOf(articleId));
        assertThat(Event.fromJson(record.value()).payload()).isEqualTo(
                new CommentCreatedEventPayload(reply.commentId(), articleId, reply.path(), false, 2L));
    }

    @Test
    @DisplayName("댓글을 지우면 줄어든 누적 댓글 수를 담은 CommentDeleted 이벤트가 도착한다")
    void deletedEventArrivesWithCumulativeCount() {
        long articleId = ARTICLE_SEQUENCE.incrementAndGet();
        write(articleId, null);
        CommentResponse deleting = write(articleId, null);

        commentService.delete(articleId, deleting.commentId());

        ConsumerRecord<String, String> record = awaitEvent(articleId, EventType.COMMENT_DELETED, deleting.commentId());
        assertThat(record.key()).isEqualTo(String.valueOf(articleId));
        assertThat(Event.fromJson(record.value()).payload()).isEqualTo(
                new CommentDeletedEventPayload(deleting.commentId(), articleId, deleting.path(), true, 1L));
    }

    private CommentResponse write(long articleId, Long parentCommentId) {
        return commentService.write(articleId, new CommentCreateRequest(1L, "본문", parentCommentId));
    }

    private ConsumerRecord<String, String> awaitEvent(long articleId, EventType type, long commentId) {
        try (KafkaConsumer<String, String> consumer = boardCommentConsumer()) {
            long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    Event<?> event = Event.fromJson(record.value());
                    if (record.key().equals(String.valueOf(articleId)) && event.type() == type
                            && commentIdOf(event).equals(commentId)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError(type + " for comment " + commentId + " did not arrive within " + ARRIVAL_TIMEOUT);
    }

    private Long commentIdOf(Event<?> event) {
        if (event.payload() instanceof CommentCreatedEventPayload created) {
            return created.commentId();
        }
        return ((CommentDeletedEventPayload) event.payload()).commentId();
    }

    private KafkaConsumer<String, String> boardCommentConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "comment-event-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(EventType.Topic.BOARD_COMMENT));
        return consumer;
    }
}
