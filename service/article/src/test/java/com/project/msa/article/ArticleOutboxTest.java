package com.project.msa.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.msa.article.ArticleDtos.ArticleCreateRequest;
import com.project.msa.article.ArticleDtos.ArticleResponse;
import com.project.msa.article.ArticleDtos.ArticleUpdateRequest;
import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.outbox.MessageRelay;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(ArticleTestConfig.class)
class ArticleOutboxTest {

    private static final AtomicLong BOARD_SEQUENCE = new AtomicLong(50_000);
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(20);
    /** 오지 않아야 하는 메시지를 확인할 때 기다리는 창. 커밋 직후 발행은 수십 ms 안에 도착한다. */
    private static final Duration ABSENCE_WINDOW = Duration.ofSeconds(3);

    @Autowired
    private ArticleService articleService;

    @Autowired
    private MessageRelay messageRelay;

    @Autowired
    private Snowflake snowflake;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient = JdbcClient.create(dataSource);
    }

    @Test
    @DisplayName("게시글을 생성하면 board-article 토픽에 articleId 키로 ArticleCreated 이벤트가 도착한다")
    void createdEventArrives() {
        long boardId = newBoard();

        ArticleResponse written = write(boardId, "생성 이벤트");

        ConsumerRecord<String, String> record = awaitEvent(written.articleId(), EventType.ARTICLE_CREATED);
        assertThat(record.key()).isEqualTo(String.valueOf(written.articleId()));
        assertThat(Event.fromJson(record.value()).payload()).isEqualTo(new ArticleCreatedEventPayload(
                written.articleId(), boardId, 7L, "생성 이벤트", "본문",
                written.createdAt(), written.modifiedAt(), 1L));
    }

    @Test
    @DisplayName("게시글을 수정하면 수정된 제목·본문을 담은 ArticleUpdated 이벤트가 도착한다")
    void updatedEventArrives() {
        long boardId = newBoard();
        ArticleResponse written = write(boardId, "수정 전");

        ArticleResponse edited = articleService.edit(boardId, written.articleId(),
                new ArticleUpdateRequest("수정 후", "바뀐 본문"));

        ConsumerRecord<String, String> record = awaitEvent(written.articleId(), EventType.ARTICLE_UPDATED);
        assertThat(record.key()).isEqualTo(String.valueOf(written.articleId()));
        assertThat(Event.fromJson(record.value()).payload()).isEqualTo(new ArticleUpdatedEventPayload(
                written.articleId(), boardId, 7L, "수정 후", "바뀐 본문",
                edited.createdAt(), edited.modifiedAt(), 1L));
    }

    @Test
    @DisplayName("게시글을 삭제하면 줄어든 게시판 게시글 수를 담은 ArticleDeleted 이벤트가 도착한다")
    void deletedEventArrives() {
        long boardId = newBoard();
        ArticleResponse written = write(boardId, "삭제할 글");

        articleService.delete(boardId, written.articleId());

        ConsumerRecord<String, String> record = awaitEvent(written.articleId(), EventType.ARTICLE_DELETED);
        assertThat(record.key()).isEqualTo(String.valueOf(written.articleId()));
        assertThat(Event.fromJson(record.value()).payload()).isEqualTo(new ArticleDeletedEventPayload(
                written.articleId(), boardId, 7L, "삭제할 글", "본문",
                written.createdAt(), written.modifiedAt(), 0L));
    }

    @Test
    @DisplayName("같은 게시판의 세 번째 게시글 생성 이벤트는 게시판 게시글 수 3을 싣는다")
    void createdEventCarriesCumulativeBoardArticleCount() {
        long boardId = newBoard();
        write(boardId, "첫째");
        write(boardId, "둘째");

        ArticleResponse third = write(boardId, "셋째");

        ConsumerRecord<String, String> record = awaitEvent(third.articleId(), EventType.ARTICLE_CREATED);
        ArticleCreatedEventPayload payload = (ArticleCreatedEventPayload) Event.fromJson(record.value()).payload();
        assertThat(payload.boardArticleCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("커밋 직후 발행에 성공하면 outbox 행이 지워진다")
    void publishedOutboxRowIsDeleted() {
        long boardId = newBoard();

        ArticleResponse written = write(boardId, "발행 후 삭제");

        awaitEvent(written.articleId(), EventType.ARTICLE_CREATED);
        awaitUntil(() -> outboxRowCount(written.articleId()) == 0);
        assertThat(outboxRowCount(written.articleId())).isZero();
    }

    @Test
    @DisplayName("비즈니스 트랜잭션이 롤백되면 outbox 행도 Kafka 메시지도 없다")
    void rolledBackTransactionLeavesNoOutboxAndNoMessage() {
        long boardId = newBoard();
        AtomicReference<ArticleResponse> rolledBack = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            rolledBack.set(write(boardId, "롤백될 글"));
            status.setRollbackOnly();
        });

        long rolledBackArticleId = rolledBack.get().articleId();
        assertThat(outboxRowCount(rolledBackArticleId)).isZero();
        assertThat(recordsFor(rolledBackArticleId, ABSENCE_WINDOW)).isEmpty();
    }

    @Test
    @DisplayName("커밋 뒤 발행되지 못하고 남은 오래된 outbox 행은 폴링이 재발행하고 지운다")
    void pollingRepublishesStaleOutboxRow() {
        long strandedArticleId = snowflake.nextId();
        insertStaleOutbox(strandedArticleId);

        messageRelay.publishPending();

        ConsumerRecord<String, String> record = awaitEvent(strandedArticleId, EventType.ARTICLE_CREATED);
        assertThat(record.key()).isEqualTo(String.valueOf(strandedArticleId));
        assertThat(outboxRowCount(strandedArticleId)).isZero();
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 outbox 행은 폴링이 건너뛰고, 잠금이 풀린 뒤 발행한다")
    void pollingSkipsRowLockedByAnotherTransaction() throws Exception {
        long lockedArticleId = snowflake.nextId();
        long outboxId = insertStaleOutbox(lockedArticleId);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread otherRelay = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            JdbcClient.create(dataSource)
                    .sql("SELECT outbox_id FROM outbox WHERE outbox_id = :outboxId FOR UPDATE")
                    .param("outboxId", outboxId)
                    .query(Long.class)
                    .single();
            rowLocked.countDown();
            try {
                releaseLock.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        otherRelay.start();
        assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

        messageRelay.publishPending();

        assertThat(outboxRowCount(lockedArticleId)).isEqualTo(1);
        assertThat(recordsFor(lockedArticleId, ABSENCE_WINDOW)).isEmpty();

        releaseLock.countDown();
        otherRelay.join(10_000);
        messageRelay.publishPending();

        awaitEvent(lockedArticleId, EventType.ARTICLE_CREATED);
        assertThat(outboxRowCount(lockedArticleId)).isZero();
    }

    private long newBoard() {
        return BOARD_SEQUENCE.incrementAndGet();
    }

    private ArticleResponse write(long boardId, String title) {
        return articleService.write(boardId, new ArticleCreateRequest(7L, title, "본문"));
    }

    /** 커밋 직후 발행이 실패해 남은 것과 같은 행을 폴링 기준보다 오래된 시각으로 직접 넣는다. */
    private long insertStaleOutbox(long articleId) {
        LocalDateTime strandedAt = LocalDateTime.now(clock).minusMinutes(1);
        Event<ArticleCreatedEventPayload> event = Event.of(snowflake.nextId(), EventType.ARTICLE_CREATED, strandedAt,
                new ArticleCreatedEventPayload(articleId, newBoard(), 7L, "남은 글", "본문", strandedAt, strandedAt, 1L));
        long outboxId = snowflake.nextId();
        jdbcClient.sql("""
                        INSERT INTO outbox (outbox_id, event_type, payload, partition_key, created_at)
                        VALUES (:outboxId, :eventType, :payload, :partitionKey, :createdAt)
                        """)
                .param("outboxId", outboxId)
                .param("eventType", EventType.ARTICLE_CREATED.name())
                .param("payload", event.toJson())
                .param("partitionKey", articleId)
                .param("createdAt", strandedAt)
                .update();
        return outboxId;
    }

    private long outboxRowCount(long articleId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE partition_key = :articleId")
                .param("articleId", articleId)
                .query(Long.class)
                .single();
    }

    private ConsumerRecord<String, String> awaitEvent(long articleId, EventType type) {
        try (KafkaConsumer<String, String> consumer = boardArticleConsumer()) {
            long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(String.valueOf(articleId))
                            && Event.fromJson(record.value()).type() == type) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError(type + " for article " + articleId + " did not arrive within " + ARRIVAL_TIMEOUT);
    }

    private List<Event<EventPayload>> recordsFor(long articleId, Duration window) {
        List<Event<EventPayload>> arrived = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = boardArticleConsumer()) {
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(String.valueOf(articleId))) {
                        arrived.add(Event.fromJson(record.value()));
                    }
                }
            }
        }
        return arrived;
    }

    private KafkaConsumer<String, String> boardArticleConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "article-outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(EventType.Topic.BOARD_ARTICLE));
        return consumer;
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + ARRIVAL_TIMEOUT);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }
}
