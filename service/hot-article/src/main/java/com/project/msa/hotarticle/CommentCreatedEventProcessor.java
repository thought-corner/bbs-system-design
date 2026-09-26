package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class CommentCreatedEventProcessor extends ArticleCountEventProcessor<CommentCreatedEventPayload> {

    CommentCreatedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(hotArticleRedisRepository, EventType.COMMENT_CREATED, HotArticleMetric.COMMENT,
                CommentCreatedEventPayload::articleId, CommentCreatedEventPayload::articleCommentCount,
                eventConsumeMetrics);
    }
}
