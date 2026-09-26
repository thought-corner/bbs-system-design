package com.project.msa.comment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "comment")
public class Comment {

    @Id
    private Long commentId;
    private Long articleId;
    private Long writerId;
    private String content;
    private String path;
    private Boolean deleted;
    private LocalDateTime createdAt;

    protected Comment() {
    }

    static Comment write(long commentId, long articleId, long writerId, String content, CommentPath commentPath,
                         LocalDateTime writtenAt) {
        Comment comment = new Comment();
        comment.commentId = commentId;
        comment.articleId = articleId;
        comment.writerId = writerId;
        comment.content = content;
        comment.path = commentPath.value();
        comment.deleted = false;
        comment.createdAt = writtenAt;
        return comment;
    }

    void markDeleted() {
        this.deleted = true;
    }

    CommentPath commentPath() {
        return CommentPath.of(path);
    }

    public Long getCommentId() {
        return commentId;
    }

    public Long getArticleId() {
        return articleId;
    }

    public Long getWriterId() {
        return writerId;
    }

    public String getContent() {
        return content;
    }

    public String getPath() {
        return path;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
