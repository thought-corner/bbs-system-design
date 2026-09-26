package com.project.msa.articleread;

import com.project.msa.articleread.ArticleReadResponse.ArticleReadPageResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/boards/{boardId}/articles")
class ArticleReadController {

    private final ArticleReadService articleReadService;

    ArticleReadController(ArticleReadService articleReadService) {
        this.articleReadService = articleReadService;
    }

    @GetMapping("/{articleId}")
    ArticleReadResponse read(@PathVariable long boardId, @PathVariable long articleId) {
        return articleReadService.read(boardId, articleId);
    }

    @GetMapping
    ArticleReadPageResponse readPage(@PathVariable long boardId, @RequestParam long page,
                                     @RequestParam long pageSize) {
        return articleReadService.readPage(boardId, page, pageSize);
    }

    @GetMapping("/infinite-scroll")
    List<ArticleReadResponse> readScroll(@PathVariable long boardId, @RequestParam long pageSize,
                                         @RequestParam(required = false) Long lastArticleId) {
        return articleReadService.readScroll(boardId, pageSize, lastArticleId);
    }
}
