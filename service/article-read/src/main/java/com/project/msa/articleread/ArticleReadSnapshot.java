package com.project.msa.articleread;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 원본을 부르기 전에 읽어 둔 상세 Hash. 원본 값을 쓸 때 그 사이 이벤트로 바뀐 필드를 가려내는 기준이다. */
record ArticleReadSnapshot(Map<String, String> fields, Optional<ArticleReadResponse> article) {

    boolean deleted() {
        return "1".equals(fields.get(ArticleReadField.DELETED));
    }

    boolean logicallyExpiredAt(Instant now) {
        String logicalExpiresAt = fields.get(ArticleReadField.LOGICAL_EXPIRES_AT);
        return logicalExpiresAt == null || !now.isBefore(Instant.ofEpochMilli(Long.parseLong(logicalExpiresAt)));
    }

    /** @return 기억한 eventId. 없었으면 빈 문자열 */
    String eventIdOf(ArticleReadField field) {
        return fields.getOrDefault(field.eventIdField(), "");
    }
}
