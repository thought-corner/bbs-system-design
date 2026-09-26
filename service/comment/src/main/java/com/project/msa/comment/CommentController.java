package com.project.msa.comment;

import com.project.msa.comment.CommentDtos.CommentCreateRequest;
import com.project.msa.comment.CommentDtos.CommentPageResponse;
import com.project.msa.comment.CommentDtos.CommentResponse;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/articles/{articleId}/comments")
class CommentController {

    private final CommentService commentService;

    CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    CommentResponse write(@PathVariable long articleId, @RequestBody CommentCreateRequest request) {
        return commentService.write(articleId, request);
    }

    @DeleteMapping("/{commentId}")
    void delete(@PathVariable long articleId, @PathVariable long commentId) {
        commentService.delete(articleId, commentId);
    }

    @GetMapping("/{commentId}")
    CommentResponse read(@PathVariable long articleId, @PathVariable long commentId) {
        return commentService.read(articleId, commentId);
    }

    @GetMapping
    CommentPageResponse readPage(@PathVariable long articleId, @RequestParam long page, @RequestParam long pageSize) {
        return commentService.readPage(articleId, page, pageSize);
    }

    @GetMapping("/infinite-scroll")
    List<CommentResponse> readScroll(@PathVariable long articleId, @RequestParam long pageSize,
                                     @RequestParam(required = false) String lastPath) {
        return commentService.readScroll(articleId, pageSize, lastPath);
    }
}
