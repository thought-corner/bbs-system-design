package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import org.springframework.stereotype.Component;

@Component
class ArticleLikedEventProcessor extends ArticleCountEventProcessor<ArticleLikedEventPayload> {

    ArticleLikedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        super(hotArticleRedisRepository, EventType.ARTICLE_LIKED, HotArticleMetric.LIKE,
                ArticleLikedEventPayload::articleId, ArticleLikedEventPayload::articleLikeCount);
    }
}
