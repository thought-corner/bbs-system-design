package com.project.msa.like;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/articles/{articleId}/likes")
class LikeController {

    private final LikeService likeService;

    LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/users/{userId}")
    void like(@PathVariable long articleId, @PathVariable long userId) {
        likeService.like(articleId, userId);
    }

    @DeleteMapping("/users/{userId}")
    void unlike(@PathVariable long articleId, @PathVariable long userId) {
        likeService.unlike(articleId, userId);
    }

    @GetMapping("/count")
    ArticleLikeCountResponse count(@PathVariable long articleId) {
        return new ArticleLikeCountResponse(articleId, likeService.count(articleId));
    }

    record ArticleLikeCountResponse(Long articleId, Long likeCount) {
    }
}
