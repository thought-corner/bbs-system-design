package com.project.msa.like;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 좋아요 쓰기는 {@link ArticleLikeRepository}의 네이티브 쿼리로 한다. 엔티티는 테이블 매핑용이다. */
@Entity
@Table(name = "article_like")
public class ArticleLike {

    @Id
    private Long articleLikeId;
    private Long articleId;
    private Long userId;
    private LocalDateTime createdAt;

    protected ArticleLike() {
    }

    public Long getArticleLikeId() {
        return articleLikeId;
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
