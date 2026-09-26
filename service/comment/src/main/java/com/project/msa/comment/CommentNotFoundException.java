package com.project.msa.comment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class CommentNotFoundException extends RuntimeException {

    CommentNotFoundException(long articleId, long commentId) {
        super("comment not found: articleId=" + articleId + ", commentId=" + commentId);
    }
}
