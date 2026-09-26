package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import org.springframework.stereotype.Component;

/** 본문을 읽기 모델에 넣고 게시판 최신 목록에 올린다. */
@Component
class ArticleCreatedReadProcessor implements ArticleReadEventProcessor<ArticleCreatedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;

    ArticleCreatedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository) {
        this.articleReadRedisRepository = articleReadRedisRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_CREATED;
    }

    @Override
    public void process(Event<ArticleCreatedEventPayload> event) {
        ArticleCreatedEventPayload created = event.payload();
        articleReadRedisRepository.applyCreated(new ArticleBody(created.articleId(), created.boardId(),
                created.writerId(), created.title(), created.content(), created.createdAt(), created.modifiedAt()),
                event.eventId());
        articleReadRedisRepository.applyBoardArticleCount(created.boardId(), created.boardArticleCount(),
                event.eventId());
    }
}
