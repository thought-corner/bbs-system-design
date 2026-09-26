package com.project.msa.common.event.payload;

import com.project.msa.common.event.EventPayload;

public record ArticleViewedEventPayload(
        Long articleId,
        Long articleViewCount
) implements EventPayload {
}
