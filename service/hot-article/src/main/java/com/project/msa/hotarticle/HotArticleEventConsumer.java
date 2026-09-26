package com.project.msa.hotarticle;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType.Topic;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class HotArticleEventConsumer {

    private final HotArticleEventHandler hotArticleEventHandler;

    HotArticleEventConsumer(HotArticleEventHandler hotArticleEventHandler) {
        this.hotArticleEventHandler = hotArticleEventHandler;
    }

    @KafkaListener(topics = {Topic.BOARD_ARTICLE, Topic.BOARD_COMMENT, Topic.BOARD_LIKE, Topic.BOARD_VIEW})
    void consume(String message) {
        hotArticleEventHandler.handle(Event.fromJson(message));
    }
}
