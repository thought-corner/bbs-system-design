package com.project.msa.articleread;

import org.springframework.web.client.RestClient;

/** comment·like 원본의 게시글 댓글 수·좋아요 수. */
class ArticleCountOriginClient {

    private final RestClient commentRestClient;
    private final RestClient likeRestClient;

    ArticleCountOriginClient(RestClient commentRestClient, RestClient likeRestClient) {
        this.commentRestClient = commentRestClient;
        this.likeRestClient = likeRestClient;
    }

    long readCommentCount(long articleId) {
        return commentRestClient.get()
                .uri("/v1/articles/{articleId}/comments/count", articleId)
                .retrieve()
                .body(ArticleCommentCountResponse.class)
                .commentCount();
    }

    long readLikeCount(long articleId) {
        return likeRestClient.get()
                .uri("/v1/articles/{articleId}/likes/count", articleId)
                .retrieve()
                .body(ArticleLikeCountResponse.class)
                .likeCount();
    }

    record ArticleCommentCountResponse(Long articleId, Long commentCount) {
    }

    record ArticleLikeCountResponse(Long articleId, Long likeCount) {
    }
}
