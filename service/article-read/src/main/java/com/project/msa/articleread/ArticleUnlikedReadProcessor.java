package com.project.msa.articleread;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class ArticleUnlikedReadProcessor extends ArticleCountReadProcessor<ArticleUnlikedEventPayload> {

    ArticleUnlikedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(articleReadRedisRepository, EventType.ARTICLE_UNLIKED, ArticleReadField.LIKE_COUNT,
                ArticleUnlikedEventPayload::articleId, ArticleUnlikedEventPayload::articleLikeCount,
                eventConsumeMetrics);
    }
}
