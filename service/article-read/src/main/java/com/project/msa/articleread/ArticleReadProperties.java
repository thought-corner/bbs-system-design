package com.project.msa.articleread;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param logicalTtl     원본으로 맞춘 뒤 다시 맞출 때까지의 시간 (D13)
 * @param refreshLockTtl 원본으로 맞추는 요청 하나만 고르는 갱신 락의 만료
 */
@ConfigurationProperties("article-read")
public record ArticleReadProperties(Duration logicalTtl, Duration refreshLockTtl, Origin origin) {

    public record Origin(String articleUrl, String commentUrl, String likeUrl, String viewUrl) {
    }
}
