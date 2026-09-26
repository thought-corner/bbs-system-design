package com.project.msa.hotarticle;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.hotarticle.HotArticleRedisRepository.ArticleCreatedDay;
import org.springframework.stereotype.Component;

/** 생성 이벤트를 받은 게시글만 인기글 후보가 되고, 점수는 생성일의 랭킹에 쌓인다 (D15). */
@Component
class ArticleCreatedEventProcessor implements HotArticleEventProcessor<ArticleCreatedEventPayload> {

    private final HotArticleRedisRepository hotArticleRedisRepository;

    ArticleCreatedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        this.hotArticleRedisRepository = hotArticleRedisRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_CREATED;
    }

    @Override
    public void process(Event<ArticleCreatedEventPayload> event) {
        ArticleCreatedEventPayload created = event.payload();
        hotArticleRedisRepository.saveCreated(created.articleId(),
                new ArticleCreatedDay(created.createdAt().toLocalDate(), created.boardId()));
    }
}
