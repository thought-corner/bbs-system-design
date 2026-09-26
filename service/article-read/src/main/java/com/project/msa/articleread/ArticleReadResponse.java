package com.project.msa.articleread;

import java.time.LocalDateTime;
import java.util.List;

public record ArticleReadResponse(Long articleId, Long boardId, Long writerId, String title, String content,
                                  LocalDateTime createdAt, LocalDateTime modifiedAt,
                                  Long articleCommentCount, Long articleLikeCount) {

    static ArticleReadResponse of(ArticleBody body, long articleCommentCount, long articleLikeCount) {
        return new ArticleReadResponse(body.articleId(), body.boardId(), body.writerId(), body.title(),
                body.content(), body.createdAt(), body.modifiedAt(), articleCommentCount, articleLikeCount);
    }

    public record ArticleReadPageResponse(List<ArticleReadResponse> articles, long articleCount) {
    }
}
