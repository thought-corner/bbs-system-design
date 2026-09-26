package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import org.springframework.stereotype.Component;

@Component
class ArticleUnlikedEventProcessor extends ArticleCountEventProcessor<ArticleUnlikedEventPayload> {

    ArticleUnlikedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        super(hotArticleRedisRepository, EventType.ARTICLE_UNLIKED, HotArticleMetric.LIKE,
                ArticleUnlikedEventPayload::articleId, ArticleUnlikedEventPayload::articleLikeCount);
    }
}
