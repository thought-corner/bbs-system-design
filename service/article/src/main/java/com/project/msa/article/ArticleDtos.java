package com.project.msa.article;

import java.time.LocalDateTime;
import java.util.List;

final class ArticleDtos {

    private ArticleDtos() {
    }

    record ArticleCreateRequest(Long writerId,
                                String title,
                                String content) {
    }

    record ArticleUpdateRequest(String title,
                                String content) {
    }

    record ArticleResponse(Long articleId,
                           Long boardId,
                           Long writerId,
                           String title,
                           String content,
                           LocalDateTime createdAt,
                           LocalDateTime modifiedAt) {

        static ArticleResponse from(Article article) {
            return new ArticleResponse(article.getArticleId(),
                    article.getBoardId(),
                    article.getWriterId(),
                    article.getTitle(),
                    article.getContent(),
                    article.getCreatedAt(),
                    article.getModifiedAt());
        }
    }

    record ArticlePageResponse(List<ArticleResponse> articles,
                               long articleCount) {
    }
}
