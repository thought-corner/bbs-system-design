package com.project.msa.article;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "article")
public class Article {

    @Id
    private Long articleId;
    private Long boardId;
    private Long writerId;
    private String title;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;

    protected Article() {
    }

    static Article write(long articleId, long boardId, long writerId, String title, String content, LocalDateTime writtenAt) {
        Article article = new Article();
        article.articleId = articleId;
        article.boardId = boardId;
        article.writerId = writerId;
        article.title = title;
        article.content = content;
        article.createdAt = writtenAt;
        article.modifiedAt = writtenAt;
        return article;
    }

    void edit(String title, String content, LocalDateTime editedAt) {
        this.title = title;
        this.content = content;
        this.modifiedAt = editedAt;
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getBoardId() {
        return boardId;
    }

    public Long getWriterId() {
        return writerId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getModifiedAt() {
        return modifiedAt;
    }
}
