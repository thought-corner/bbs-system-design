package com.project.msa.common.event;

import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;

public enum EventType {

    ARTICLE_CREATED(ArticleCreatedEventPayload.class, Topic.BOARD_ARTICLE),
    ARTICLE_UPDATED(ArticleUpdatedEventPayload.class, Topic.BOARD_ARTICLE),
    ARTICLE_DELETED(ArticleDeletedEventPayload.class, Topic.BOARD_ARTICLE),
    COMMENT_CREATED(CommentCreatedEventPayload.class, Topic.BOARD_COMMENT),
    COMMENT_DELETED(CommentDeletedEventPayload.class, Topic.BOARD_COMMENT);

    private final Class<? extends EventPayload> payloadClass;
    private final String topic;

    EventType(Class<? extends EventPayload> payloadClass, String topic) {
        this.payloadClass = payloadClass;
        this.topic = topic;
    }

    public Class<? extends EventPayload> payloadClass() {
        return payloadClass;
    }

    public String topic() {
        return topic;
    }

    public static final class Topic {

        public static final String BOARD_ARTICLE = "board-article";
        public static final String BOARD_COMMENT = "board-comment";

        private Topic() {
        }
    }
}
