package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

/** 삭제 표시만 남기고 게시판 목록에서 뺀다. 늦게 온 이벤트로 되살아나지 않는다. */
@Component
class ArticleDeletedReadProcessor implements ArticleReadEventProcessor<ArticleDeletedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final EventConsumeMetrics eventConsumeMetrics;

    ArticleDeletedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
                                EventConsumeMetrics eventConsumeMetrics) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.eventConsumeMetrics = eventConsumeMetrics;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_DELETED;
    }

    @Override
    public void process(Event<ArticleDeletedEventPayload> event) {
        ArticleDeletedEventPayload deleted = event.payload();
        boolean applied = articleReadRedisRepository.applyDeleted(deleted.articleId(), deleted.boardId(), event.eventId());
        if (!applied) {
            eventConsumeMetrics.recordStale(supportedType());
        }
        articleReadRedisRepository.applyBoardArticleCount(deleted.boardId(), deleted.boardArticleCount(),
                event.eventId());
    }
}
