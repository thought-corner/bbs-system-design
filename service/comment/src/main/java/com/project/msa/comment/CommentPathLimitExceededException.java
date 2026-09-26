package com.project.msa.comment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class CommentPathLimitExceededException extends RuntimeException {

    CommentPathLimitExceededException(String message) {
        super(message);
    }
}
