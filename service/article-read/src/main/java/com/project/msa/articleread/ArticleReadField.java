package com.project.msa.articleread;

/** 상세 읽기 모델 Hash의 필드와 그 필드를 마지막으로 바꾼 eventId를 두는 필드 (D12). */
enum ArticleReadField {

    ARTICLE("article", "article-event-id"),
    COMMENT_COUNT("comment-count", "comment-event-id"),
    LIKE_COUNT("like-count", "like-event-id");

    static final String DELETED = "deleted";

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
