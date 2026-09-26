package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import org.springframework.stereotype.Component;

@Component
class CommentCreatedEventProcessor extends ArticleCountEventProcessor<CommentCreatedEventPayload> {

    CommentCreatedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        super(hotArticleRedisRepository, EventType.COMMENT_CREATED, HotArticleMetric.COMMENT,
                CommentCreatedEventPayload::articleId, CommentCreatedEventPayload::articleCommentCount);
    }
}
