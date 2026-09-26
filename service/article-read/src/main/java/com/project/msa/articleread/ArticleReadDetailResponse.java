package com.project.msa.articleread;

import java.time.LocalDateTime;

/** 상세 응답. 읽기 모델 값에 view 서비스가 돌려준 조회수를 붙인다. 조회수는 view가 실패하면 비어 있다. */
public record ArticleReadDetailResponse(Long articleId, Long boardId, Long writerId, String title, String content,
                                        LocalDateTime createdAt, LocalDateTime modifiedAt,
                                        Long articleCommentCount, Long articleLikeCount, Long viewCount) {

    static ArticleReadDetailResponse of(ArticleReadResponse article, Long viewCount) {
        return new ArticleReadDetailResponse(article.articleId(), article.boardId(), article.writerId(),
                article.title(), article.content(), article.createdAt(), article.modifiedAt(),
                article.articleCommentCount(), article.articleLikeCount(), viewCount);
    }
}
