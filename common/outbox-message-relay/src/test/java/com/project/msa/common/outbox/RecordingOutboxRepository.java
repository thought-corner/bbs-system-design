package com.project.msa.common.outbox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** DB 없이 지운 행을 기록하고, 폴링이 잠글 행을 미리 정해 두는 저장소 대역. */
class RecordingOutboxRepository extends OutboxRepository {

    final Set<Long> deletedIds = ConcurrentHashMap.newKeySet();
    private final List<Outbox> pendingOutboxes = new ArrayList<>();

    RecordingOutboxRepository() {
        super(null);
    }

    void pending(List<Outbox> outboxes) {
        pendingOutboxes.addAll(outboxes);
    }

    @Override
    List<Outbox> lockPendingCreatedBefore(LocalDateTime pendingBefore, int limit) {
        return List.copyOf(pendingOutboxes);
    }

    @Override
    void deleteAll(List<Long> outboxIds) {
        deletedIds.addAll(outboxIds);
    }
}
