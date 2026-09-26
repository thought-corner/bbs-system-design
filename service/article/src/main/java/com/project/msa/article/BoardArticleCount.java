package com.project.msa.article;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "board_article_count")
public class BoardArticleCount {

    @Id
    private Long boardId;
    private Long articleCount;

    protected BoardArticleCount() {
    }

    public Long getBoardId() {
        return boardId;
    }

    public Long getArticleCount() {
        return articleCount;
    }
}
