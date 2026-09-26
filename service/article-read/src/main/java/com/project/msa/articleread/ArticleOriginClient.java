package com.project.msa.articleread;

import java.util.List;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** article 원본. 읽기 모델에 없는 게시글과 최신 목록 밖의 페이지를 읽는다 (D12). */
class ArticleOriginClient {

    private final RestClient restClient;

    ArticleOriginClient(RestClient restClient) {
        this.restClient = restClient;
    }

    Optional<ArticleBody> readArticle(long boardId, long articleId) {
        return Optional.ofNullable(restClient.get()
                .uri("/v1/boards/{boardId}/articles/{articleId}", boardId, articleId)
                .exchange((request, response) -> response.getStatusCode() == HttpStatus.NOT_FOUND
                        ? null
                        : response.bodyTo(ArticleBody.class)));
    }

    OriginArticlePage readPage(long boardId, long page, long pageSize) {
        return restClient.get()
                .uri("/v1/boards/{boardId}/articles?page={page}&pageSize={pageSize}", boardId, page, pageSize)
                .retrieve()
                .body(OriginArticlePage.class);
    }

    List<ArticleBody> readScroll(long boardId, long pageSize, Long lastArticleId) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/boards/{boardId}/articles/infinite-scroll").queryParam("pageSize", pageSize);
                    if (lastArticleId != null) {
                        uriBuilder.queryParam("lastArticleId", lastArticleId);
                    }
                    return uriBuilder.build(boardId);
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    record OriginArticlePage(List<ArticleBody> articles, long articleCount) {
    }
}
