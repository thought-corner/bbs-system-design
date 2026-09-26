package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventType.Topic;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ArticleReadEventConsumer {

    private final ArticleReadEventHandler articleReadEventHandler;

    ArticleReadEventConsumer(ArticleReadEventHandler articleReadEventHandler) {
        this.articleReadEventHandler = articleReadEventHandler;
    }

    @KafkaListener(topics = {Topic.BOARD_ARTICLE, Topic.BOARD_COMMENT, Topic.BOARD_LIKE})
    void consume(String message) {
        articleReadEventHandler.handle(Event.fromJson(message));
    }
}
