package com.project.msa.article;

import com.project.msa.article.ArticleDtos.ArticleCreateRequest;
import com.project.msa.article.ArticleDtos.ArticlePageResponse;
import com.project.msa.article.ArticleDtos.ArticleResponse;
import com.project.msa.article.ArticleDtos.ArticleUpdateRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/boards/{boardId}/articles")
class ArticleController {

    private final ArticleService articleService;

    ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @PostMapping
    ArticleResponse write(@PathVariable long boardId, @RequestBody ArticleCreateRequest request) {
        return articleService.write(boardId, request);
    }

    @PutMapping("/{articleId}")
    ArticleResponse edit(@PathVariable long boardId, @PathVariable long articleId,
                         @RequestBody ArticleUpdateRequest request) {
        return articleService.edit(boardId, articleId, request);
    }

    @DeleteMapping("/{articleId}")
    void delete(@PathVariable long boardId, @PathVariable long articleId) {
        articleService.delete(boardId, articleId);
    }

    @GetMapping("/{articleId}")
    ArticleResponse read(@PathVariable long boardId, @PathVariable long articleId) {
        return articleService.read(boardId, articleId);
    }

    @GetMapping
    ArticlePageResponse readPage(@PathVariable long boardId, @RequestParam long page, @RequestParam long pageSize) {
        return articleService.readPage(boardId, page, pageSize);
    }

    @GetMapping("/infinite-scroll")
    List<ArticleResponse> readScroll(@PathVariable long boardId, @RequestParam long pageSize,
                                     @RequestParam(required = false) Long lastArticleId) {
        return articleService.readScroll(boardId, pageSize, lastArticleId);
    }
}
