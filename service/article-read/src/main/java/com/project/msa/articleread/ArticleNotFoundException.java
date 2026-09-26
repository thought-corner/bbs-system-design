package com.project.msa.articleread;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class ArticleNotFoundException extends RuntimeException {

    ArticleNotFoundException(long boardId, long articleId) {
        super("article not found in read model: boardId=" + boardId + ", articleId=" + articleId);
    }
}
