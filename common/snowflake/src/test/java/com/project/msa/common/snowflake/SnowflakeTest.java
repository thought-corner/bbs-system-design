package com.project.msa.common.snowflake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnowflakeTest {

    private static final long FIXED_MILLIS = Snowflake.START_EPOCH_MILLIS + 1_000L;

    @Test
    @DisplayName("한 생성기에서 10만 건을 만들어도 중복이 없다")
    void noDuplicatesInSingleGenerator() {
        Snowflake snowflake = new Snowflake(1);

        Set<Long> issuedIds = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            issuedIds.add(snowflake.nextId());
        }

        assertThat(issuedIds).hasSize(100_000);
    }

    @Test
    @DisplayName("순차로 만든 ID는 단조 증가하고 양수다")
    void idsIncreaseMonotonically() {
        Snowflake snowflake = new Snowflake(1);

        long previousId = snowflake.nextId();
        for (int i = 0; i < 10_000; i++) {
            long issuedId = snowflake.nextId();

            assertThat(issuedId).isPositive().isGreaterThan(previousId);
            previousId = issuedId;
        }
    }

    @Test
    @DisplayName("여러 스레드가 동시에 만들어도 중복이 없다")
    void noDuplicatesUnderConcurrency() throws InterruptedException {
        Snowflake snowflake = new Snowflake(1);
        int threadCount = 8;
        int idsPerThread = 10_000;
        Set<Long> issuedIds = ConcurrentHashMap.newKeySet();
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                startSignal.await();
                for (int i = 0; i < idsPerThread; i++) {
                    issuedIds.add(snowflake.nextId());
                }
                return null;
            });
        }
        startSignal.countDown();
        executor.shutdown();

        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(issuedIds).hasSize(threadCount * idsPerThread);
    }

    @Test
    @DisplayName("노드 ID가 다른 두 생성기가 같은 밀리초에 만든 ID는 겹치지 않는다")
    void differentNodesNeverCollideInSameMillis() {
        Snowflake firstNode = new Snowflake(1, () -> FIXED_MILLIS);
        Snowflake secondNode = new Snowflake(2, () -> FIXED_MILLIS);

        Set<Long> issuedIds = new HashSet<>();
        for (int i = 0; i <= Snowflake.MAX_SEQUENCE; i++) {
            issuedIds.add(firstNode.nextId());
            issuedIds.add(secondNode.nextId());
        }

        assertThat(issuedIds).hasSize(2 * (int) (Snowflake.MAX_SEQUENCE + 1));
    }

    @Test
    @DisplayName("같은 밀리초에 순번을 다 쓰면 다음 밀리초까지 기다려 중복을 만들지 않는다")
    void waitsForNextMillisWhenSequenceExhausted() {
        AtomicLong clockReads = new AtomicLong();
        long sequenceCapacity = Snowflake.MAX_SEQUENCE + 1;
        // 순번을 다 쓴 뒤 몇 번 더 같은 밀리초를 보여 주고 나서야 시계가 넘어간다
        Snowflake snowflake = new Snowflake(1, () ->
                clockReads.incrementAndGet() <= sequenceCapacity + 3 ? FIXED_MILLIS : FIXED_MILLIS + 1);

        List<Long> issuedIds = new ArrayList<>();
        for (int i = 0; i <= sequenceCapacity; i++) {
            issuedIds.add(snowflake.nextId());
        }

        assertThat(new HashSet<>(issuedIds)).hasSize(issuedIds.size());
        long overflowedId = issuedIds.get(issuedIds.size() - 1);
        assertThat(overflowedId).isGreaterThan(issuedIds.get(issuedIds.size() - 2));
        assertThat(overflowedId >>> 22).isEqualTo(FIXED_MILLIS + 1 - Snowflake.START_EPOCH_MILLIS);
    }

    @Test
    @DisplayName("시계가 뒤로 가면 예외를 던진다")
    void rejectsClockMovingBackwards() {
        AtomicLong nowMillis = new AtomicLong(FIXED_MILLIS);
        Snowflake snowflake = new Snowflake(1, nowMillis::get);
        snowflake.nextId();

        nowMillis.set(FIXED_MILLIS - 1);

        assertThatThrownBy(snowflake::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clock moved backwards");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 1024L})
    @DisplayName("노드 ID가 0~1023 밖이면 생성기를 만들 때 예외를 던진다")
    void rejectsNodeIdOutOfRange(long nodeId) {
        assertThatThrownBy(() -> new Snowflake(nodeId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1023L})
    @DisplayName("노드 ID 0과 1023은 ID 안에 그대로 담긴다")
    void acceptsNodeIdBoundaries(long nodeId) {
        Snowflake snowflake = new Snowflake(nodeId, () -> FIXED_MILLIS);

        long issuedId = snowflake.nextId();

        assertThat((issuedId >>> 12) & Snowflake.MAX_NODE_ID).isEqualTo(nodeId);
    }
}
