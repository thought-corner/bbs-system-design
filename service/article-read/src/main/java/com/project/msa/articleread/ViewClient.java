package com.project.msa.articleread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/** 상세 조회가 곧 조회이므로 view 서비스의 증가 API를 부른다 (D12). */
class ViewClient {

    private static final Logger log = LoggerFactory.getLogger(ViewClient.class);

    private final RestClient restClient;

    ViewClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** @return 증가 후 조회수. view가 실패하면 null — 상세 응답은 조회수 없이 나간다 */
    Long increase(long articleId, long userId) {
        try {
            return restClient.post()
                    .uri("/v1/articles/{articleId}/views/users/{userId}", articleId, userId)
                    .retrieve()
                    .body(ArticleViewCountResponse.class)
                    .viewCount();
        } catch (RuntimeException viewFailure) {
            log.warn("view increase failed, responding without view count: articleId={}", articleId, viewFailure);
            return null;
        }
    }

    record ArticleViewCountResponse(Long articleId, Long viewCount) {
    }
}
