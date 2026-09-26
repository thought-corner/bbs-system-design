package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

/** 제목·본문을 바꾼다. 게시판 목록과 게시글 수는 그대로다. */
@Component
class ArticleUpdatedReadProcessor implements ArticleReadEventProcessor<ArticleUpdatedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final EventConsumeMetrics eventConsumeMetrics;

    ArticleUpdatedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
                                EventConsumeMetrics eventConsumeMetrics) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.eventConsumeMetrics = eventConsumeMetrics;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_UPDATED;
    }

    @Override
    public void process(Event<ArticleUpdatedEventPayload> event) {
        ArticleUpdatedEventPayload updated = event.payload();
        boolean applied = articleReadRedisRepository.applyUpdated(new ArticleBody(updated.articleId(), updated.boardId(),
                updated.writerId(), updated.title(), updated.content(), updated.createdAt(), updated.modifiedAt()),
                event.eventId());
        if (applied) {
            eventConsumeMetrics.recordApplied(event);
        } else {
            eventConsumeMetrics.recordStale(supportedType());
        }
    }
}
