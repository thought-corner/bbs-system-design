package com.project.msa.common.monitoring;

import java.time.Duration;

/** 비동기로 오르는 지표(커밋 직후 발행 등)를 상한 있는 폴링으로 기다린다. 고정 sleep을 쓰지 않는다. */
public final class Eventually {

    private Eventually() {
    }

    public static void until(Condition condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }

    @FunctionalInterface
    public interface Condition {
        boolean holds() throws Exception;
    }
}
