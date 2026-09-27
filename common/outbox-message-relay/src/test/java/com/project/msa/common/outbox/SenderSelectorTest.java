package com.project.msa.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SenderSelectorTest {

    private static final int SENDER_COUNT = 4;

    @Test
    @DisplayName("순번이 0인 Snowflake ID(연속된 밀리초) 1만 개가 발행 스레드 4개에 고르게 퍼진다")
    void spreadsSnowflakeIdsWithZeroSequenceAcrossSenders() {
        long nodeId = 100;
        long firstMillis = 23_000_000_000L;
        int[] assigned = new int[SENDER_COUNT];

        for (int i = 0; i < 10_000; i++) {
            long snowflakeId = ((firstMillis + i) << 22) | (nodeId << 12);
            assigned[SenderSelector.senderIndex(snowflakeId, SENDER_COUNT)]++;
        }

        for (int count : assigned) {
            assertThat(count).isBetween(1_500, 3_500);
        }
    }

    @Test
    @DisplayName("음수·최댓값 키도 범위 안의 발행 스레드를 고른다")
    void staysInRangeForExtremeKeys() {
        for (long partitionKey : new long[] {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}) {
            assertThat(SenderSelector.senderIndex(partitionKey, SENDER_COUNT)).isBetween(0, SENDER_COUNT - 1);
        }
    }
}
