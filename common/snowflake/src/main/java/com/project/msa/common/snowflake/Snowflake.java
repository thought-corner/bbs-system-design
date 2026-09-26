package com.project.msa.common.snowflake;

import java.util.function.LongSupplier;

/**
 * 시각 41비트 + 노드 10비트 + 순번 12비트로 전역 유일·시간순 ID를 만든다.
 * 부호 비트는 쓰지 않으므로 ID는 항상 양수다.
 */
public class Snowflake {

    private static final int NODE_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;
    static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /** 2026-01-01T00:00:00Z. 41비트 시각은 여기서 약 69년을 센다. */
    static final long START_EPOCH_MILLIS = 1767225600000L;

    private final long nodeId;
    private final LongSupplier clock;

    private long lastIssuedMillis = -1L;
    private long sequence = 0L;

    public Snowflake(long nodeId) {
        this(nodeId, System::currentTimeMillis);
    }

    Snowflake(long nodeId, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE_ID + ": " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long issuingMillis = clock.getAsLong();

        if (issuingMillis < lastIssuedMillis) {
            throw new IllegalStateException(
                    "clock moved backwards: last issued at " + lastIssuedMillis + ", now " + issuingMillis);
        }

        if (issuingMillis == lastIssuedMillis) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                issuingMillis = waitUntilAfter(lastIssuedMillis);
            }
        } else {
            sequence = 0;
        }

        lastIssuedMillis = issuingMillis;
        return ((issuingMillis - START_EPOCH_MILLIS) << (NODE_ID_BITS + SEQUENCE_BITS))
                | (nodeId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitUntilAfter(long exhaustedMillis) {
        long nowMillis = clock.getAsLong();
        while (nowMillis <= exhaustedMillis) {
            Thread.onSpinWait();
            nowMillis = clock.getAsLong();
        }
        return nowMillis;
    }
}
