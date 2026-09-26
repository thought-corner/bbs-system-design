package com.project.msa.common.event.payload;

import com.project.msa.common.event.EventPayload;

public record CommentCreatedEventPayload(
        Long commentId,
        Long articleId,
        String path,
        Boolean deleted,
        Long articleCommentCount
) implements EventPayload {
}
