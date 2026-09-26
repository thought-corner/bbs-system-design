package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class ArticleUnlikedEventProcessor extends ArticleCountEventProcessor<ArticleUnlikedEventPayload> {

    ArticleUnlikedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(hotArticleRedisRepository, EventType.ARTICLE_UNLIKED, HotArticleMetric.LIKE,
                ArticleUnlikedEventPayload::articleId, ArticleUnlikedEventPayload::articleLikeCount,
                eventConsumeMetrics);
    }
}
