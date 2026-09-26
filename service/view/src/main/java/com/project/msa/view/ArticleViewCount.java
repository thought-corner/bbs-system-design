package com.project.msa.view;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 조회수 백업. 쓰기는 {@link ArticleViewCountBackupRepository}의 네이티브 쿼리로 한다. */
@Entity
@Table(name = "article_view_count")
public class ArticleViewCount {

    @Id
    private Long articleId;
    private Long viewCount;

    protected ArticleViewCount() {
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getViewCount() {
        return viewCount;
    }
}
