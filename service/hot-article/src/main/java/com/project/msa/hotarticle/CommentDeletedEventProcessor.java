package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class CommentDeletedEventProcessor extends ArticleCountEventProcessor<CommentDeletedEventPayload> {

    CommentDeletedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(hotArticleRedisRepository, EventType.COMMENT_DELETED, HotArticleMetric.COMMENT,
                CommentDeletedEventPayload::articleId, CommentDeletedEventPayload::articleCommentCount,
                eventConsumeMetrics);
    }
}
