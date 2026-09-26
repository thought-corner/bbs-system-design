package com.project.msa.articleread;

/** 상세 읽기 모델 Hash의 필드와 그 필드를 마지막으로 바꾼 eventId를 두는 필드 (D12). */
enum ArticleReadField {

    ARTICLE("article", "article-event-id"),
    COMMENT_COUNT("comment-count", "comment-event-id"),
    LIKE_COUNT("like-count", "like-event-id");

    static final String DELETED = "deleted";
    /** 이 시각이 지나면 원본과 다시 맞춘다 (D13). 원본으로 채울 때와 생성 이벤트로 처음 만들 때만 쓴다. */
    static final String LOGICAL_EXPIRES_AT = "logical-expires-at";

    private final String field;
    private final String eventIdField;

    ArticleReadField(String field, String eventIdField) {
        this.field = field;
        this.eventIdField = eventIdField;
    }

    String field() {
        return field;
    }

    String eventIdField() {
        return eventIdField;
    }
}
