package com.project.msa.view;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import com.project.msa.common.outbox.OutboxEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 백업 쓰기와 `ArticleViewed`의 outbox 저장을 한 트랜잭션으로 묶는다. */
@Service
public class ViewCountBackupService {

    private final ArticleViewCountBackupRepository articleViewCountBackupRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    ViewCountBackupService(ArticleViewCountBackupRepository articleViewCountBackupRepository,
                           OutboxEventPublisher outboxEventPublisher) {
        this.articleViewCountBackupRepository = articleViewCountBackupRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional
    public void backup(long articleId, long viewCount) {
        articleViewCountBackupRepository.backup(articleId, viewCount);
        outboxEventPublisher.publish(EventType.ARTICLE_VIEWED,
                new ArticleViewedEventPayload(articleId, viewCount), articleId);
    }

    @Transactional(readOnly = true)
    public long findBackupViewCount(long articleId) {
        return articleViewCountBackupRepository.findViewCount(articleId).orElse(0L);
    }
}
