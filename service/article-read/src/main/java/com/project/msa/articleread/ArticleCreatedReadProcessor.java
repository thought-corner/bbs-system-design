package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import java.time.Clock;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

/** 본문을 읽기 모델에 넣고 게시판 최신 목록에 올린다. 처음 들어올 때 논리 만료를 둔다 (D13). */
@Component
class ArticleCreatedReadProcessor implements ArticleReadEventProcessor<ArticleCreatedEventPayload> {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final ArticleReadProperties properties;
    private final Clock clock;
    private final EventConsumeMetrics eventConsumeMetrics;

    ArticleCreatedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
                                ArticleReadProperties properties, Clock clock,
                                EventConsumeMetrics eventConsumeMetrics) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.eventConsumeMetrics = eventConsumeMetrics;
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
        boolean applied = articleReadRedisRepository.applyCreated(new ArticleBody(created.articleId(), created.boardId(),
                created.writerId(), created.title(), created.content(), created.createdAt(), created.modifiedAt()),
                event.eventId(), clock.instant().plus(properties.logicalTtl()));
        if (!applied) {
            eventConsumeMetrics.recordStale(supportedType());
        }
        articleReadRedisRepository.applyBoardArticleCount(created.boardId(), created.boardArticleCount(),
                event.eventId());
    }
}
