package com.project.msa.common.outbox;

/**
 * 파티션 키로 발행 스레드를 고른다. 같은 키는 늘 같은 스레드로 가서 보내는 순서가 커밋 순서를 따른다 (D11).
 * 키(게시글 ID)는 Snowflake라 하위 12비트가 같은 밀리초 안 순번이고 대개 0이다.
 * 그래서 `키 % 스레드 수`로 고르면 거의 모든 키가 한 스레드로 몰린다 (v1.0.0 실측: 발행이 서비스당 초당 약 75건에서 막힘).
 * 비트를 섞는 SplitMix64 마무리 함수로 시각·노드 비트까지 고르게 반영한다.
 */
final class SenderSelector {

    private SenderSelector() {
    }

    static int senderIndex(long partitionKey, int senderCount) {
        long mixed = partitionKey;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        mixed = mixed ^ (mixed >>> 31);
        return (int) Math.floorMod(mixed, (long) senderCount);
    }
}
