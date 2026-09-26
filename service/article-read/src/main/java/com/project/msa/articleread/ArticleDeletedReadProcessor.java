package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import org.springframework.stereotype.Component;

/** 삭제 표시만 남기고 게시판 목록에서 뺀다. 늦게 온 이벤트로 되살아나지 않는다. */
@Component
class ArticleDeletedReadProcessor implements ArticleReadEventProcessor<ArticleDeletedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;

    ArticleDeletedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository) {
        this.articleReadRedisRepository = articleReadRedisRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_DELETED;
    }

    @Override
    public void process(Event<ArticleDeletedEventPayload> event) {
        ArticleDeletedEventPayload deleted = event.payload();
        articleReadRedisRepository.applyDeleted(deleted.articleId(), deleted.boardId(), event.eventId());
        articleReadRedisRepository.applyBoardArticleCount(deleted.boardId(), deleted.boardArticleCount(),
                event.eventId());
    }
}
