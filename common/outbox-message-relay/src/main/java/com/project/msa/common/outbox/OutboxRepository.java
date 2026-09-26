package com.project.msa.common.outbox;

import com.project.msa.common.event.EventType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 비즈니스 트랜잭션에 참여하도록 같은 DataSource 위의 JdbcClient로 쓴다.
 */
class OutboxRepository {

    private final JdbcClient jdbcClient;

    OutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void save(Outbox outbox) {
        jdbcClient.sql("""
                        INSERT INTO outbox (outbox_id, event_type, payload, partition_key, created_at)
                        VALUES (:outboxId, :eventType, :payload, :partitionKey, :createdAt)
                        """)
                .param("outboxId", outbox.outboxId())
                .param("eventType", outbox.eventType().name())
                .param("payload", outbox.payload())
                .param("partitionKey", outbox.partitionKey())
                .param("createdAt", outbox.createdAt())
                .update();
    }

    /**
     * 다른 인스턴스가 잠근 행은 건너뛰어 한 행을 한 곳만 발행한다 (D11).
     */
    List<Outbox> lockPendingCreatedBefore(LocalDateTime pendingBefore, int limit) {
        return jdbcClient.sql("""
                        SELECT outbox_id, event_type, payload, partition_key, created_at
                        FROM outbox
                        WHERE created_at <= :pendingBefore
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("pendingBefore", pendingBefore)
                .param("limit", limit)
                .query((rs, rowNum) -> new Outbox(
                        rs.getLong("outbox_id"),
                        EventType.valueOf(rs.getString("event_type")),
                        rs.getString("payload"),
                        rs.getLong("partition_key"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    /** 최대 `limit`행까지만 센다. 적체가 그보다 크면 `limit`을 돌려준다. */
    long countPendingUpTo(int limit) {
        return jdbcClient.sql("SELECT COUNT(*) FROM (SELECT 1 FROM outbox LIMIT :limit) pending_rows")
                .param("limit", limit)
                .query(Long.class)
                .single();
    }

    /** `idx_created_at` 인덱스의 첫 항목만 읽는다. */
    Optional<LocalDateTime> findOldestCreatedAt() {
        return jdbcClient.sql("SELECT created_at FROM outbox ORDER BY created_at LIMIT 1")
                .query(LocalDateTime.class)
                .optional();
    }

    void delete(long outboxId) {
        jdbcClient.sql("DELETE FROM outbox WHERE outbox_id = :outboxId")
                .param("outboxId", outboxId)
                .update();
    }
}
