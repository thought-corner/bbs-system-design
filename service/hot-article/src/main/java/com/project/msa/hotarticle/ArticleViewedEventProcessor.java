package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class ArticleViewedEventProcessor extends ArticleCountEventProcessor<ArticleViewedEventPayload> {

    ArticleViewedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(hotArticleRedisRepository, EventType.ARTICLE_VIEWED, HotArticleMetric.VIEW,
                ArticleViewedEventPayload::articleId, ArticleViewedEventPayload::articleViewCount,
                eventConsumeMetrics);
    }
}
