package com.project.msa.view;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/articles/{articleId}/views")
class ViewController {

    private final ViewService viewService;

    ViewController(ViewService viewService) {
        this.viewService = viewService;
    }

    @PostMapping("/users/{userId}")
    ArticleViewCountResponse increase(@PathVariable long articleId, @PathVariable long userId) {
        return new ArticleViewCountResponse(articleId, viewService.increase(articleId, userId));
    }

    @GetMapping("/count")
    ArticleViewCountResponse count(@PathVariable long articleId) {
        return new ArticleViewCountResponse(articleId, viewService.count(articleId));
    }

    record ArticleViewCountResponse(Long articleId, Long viewCount) {
    }
}
