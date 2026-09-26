package com.project.msa.hotarticle;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import org.springframework.stereotype.Component;

@Component
class CommentDeletedEventProcessor extends ArticleCountEventProcessor<CommentDeletedEventPayload> {

    CommentDeletedEventProcessor(HotArticleRedisRepository hotArticleRedisRepository) {
        super(hotArticleRedisRepository, EventType.COMMENT_DELETED, HotArticleMetric.COMMENT,
                CommentDeletedEventPayload::articleId, CommentDeletedEventPayload::articleCommentCount);
    }
}
