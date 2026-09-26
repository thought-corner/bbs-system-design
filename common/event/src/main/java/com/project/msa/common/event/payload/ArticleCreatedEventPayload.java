package com.project.msa.common.event.payload;

import com.project.msa.common.event.EventPayload;
import java.time.LocalDateTime;

public record ArticleCreatedEventPayload(
        Long articleId,
        Long boardId,
        Long writerId,
        String title,
        String content,
        LocalDateTime createdAt,
        LocalDateTime modifiedAt,
        Long boardArticleCount
) implements EventPayload {
}
