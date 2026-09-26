package com.project.msa.articleread;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import org.springframework.stereotype.Component;

@Component
class CommentDeletedReadProcessor extends ArticleCountReadProcessor<CommentDeletedEventPayload> {

    CommentDeletedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository,
            EventConsumeMetrics eventConsumeMetrics) {
        super(articleReadRedisRepository, EventType.COMMENT_DELETED, ArticleReadField.COMMENT_COUNT,
                CommentDeletedEventPayload::articleId, CommentDeletedEventPayload::articleCommentCount,
                eventConsumeMetrics);
    }
}
