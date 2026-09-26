package com.project.msa.common.event.payload;

import com.project.msa.common.event.EventPayload;

public record ArticleUnlikedEventPayload(
        Long articleId,
        Long userId,
        Long articleLikeCount
) implements EventPayload {
}
