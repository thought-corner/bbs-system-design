package com.project.msa.comment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "article_comment_count")
public class ArticleCommentCount {

    @Id
    private Long articleId;
    private Long commentCount;

    protected ArticleCommentCount() {
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getCommentCount() {
        return commentCount;
    }
}
