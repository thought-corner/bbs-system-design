package com.project.msa.hotarticle;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import org.springframework.stereotype.Component;

/** 삭제된 게시글은 집계 중 랭킹에서 빼고 이후 이벤트를 버린다. 확정된 목록은 건드리지 않는다. */
@Component
class ArticleDeletedEventProcessor implements HotArticleEventProcessor<ArticleDeletedEventPayload> {

    private final HotArticleRedisRepository hotArticleRedisRepository;

    ArticleDeletedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        this.hotArticleRedisRepository = hotArticleRedisRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.ARTICLE_DELETED;
    }

    @Override
    public void process(Event<ArticleDeletedEventPayload> event) {
        long articleId = event.payload().articleId();
        hotArticleRedisRepository.findCreated(articleId).ifPresent(createdDay ->
                hotArticleRedisRepository.excludeDeleted(articleId, createdDay.createdDate()));
    }
}
