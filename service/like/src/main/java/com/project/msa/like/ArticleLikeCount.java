package com.project.msa.like;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "article_like_count")
public class ArticleLikeCount {

    @Id
    private Long articleId;
    private Long likeCount;

    protected ArticleLikeCount() {
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getLikeCount() {
        return likeCount;
    }
}
