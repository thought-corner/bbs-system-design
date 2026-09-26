package com.project.msa.articleread;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import org.springframework.stereotype.Component;

@Component
class CommentCreatedReadProcessor extends ArticleCountReadProcessor<CommentCreatedEventPayload> {

    CommentCreatedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository) {
        super(articleReadRedisRepository, EventType.COMMENT_CREATED, ArticleReadField.COMMENT_COUNT,
                CommentCreatedEventPayload::articleId, CommentCreatedEventPayload::articleCommentCount);
    }
}
