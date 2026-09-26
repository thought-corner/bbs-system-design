package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class ArticleLikedEventProcessor extends ArticleCountEventProcessor<ArticleLikedEventPayload> {

    ArticleLikedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(hotArticleRedisRepository, EventType.ARTICLE_LIKED, HotArticleMetric.LIKE,
                ArticleLikedEventPayload::articleId, ArticleLikedEventPayload::articleLikeCount,
                eventConsumeMetrics);
    }
}
