package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** 본문을 읽기 모델에 넣고 게시판 최신 목록에 올린다. 처음 들어올 때 논리 만료를 둔다 (D13). */
@Component
class ArticleCreatedReadProcessor implements ArticleReadEventProcessor<ArticleCreatedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final ArticleReadProperties properties;
    private final Clock clock;

    ArticleCreatedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
                                ArticleReadProperties properties, Clock clock) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.properties = properties;
        this.clock = clock;
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
                event.eventId(), clock.instant().plus(properties.logicalTtl()));
        articleReadRedisRepository.applyBoardArticleCount(created.boardId(), created.boardArticleCount(),
                event.eventId());
    }
}
