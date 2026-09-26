package com.project.msa.common.event;

import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;

public enum EventType {

    ARTICLE_CREATED(ArticleCreatedEventPayload.class, Topic.BOARD_ARTICLE),
    ARTICLE_UPDATED(ArticleUpdatedEventPayload.class, Topic.BOARD_ARTICLE),
    ARTICLE_DELETED(ArticleDeletedEventPayload.class, Topic.BOARD_ARTICLE);

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

        private Topic() {
        }
    }
}
