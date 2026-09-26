package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import java.util.function.ToLongFunction;

/** 댓글 수·좋아요 수 이벤트 처리기. 하위 클래스는 맡을 타입·필드·추출 방법만 선언한다. */
abstract class ArticleCountReadProcessor<T extends EventPayload> implements ArticleReadEventProcessor<T> {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final EventType supportedType;
    private final ArticleReadField countField;
    private final ToLongFunction<T> articleIdOf;
    private final ToLongFunction<T> countOf;
    private final EventConsumeMetrics eventConsumeMetrics;

    ArticleCountReadProcessor(ArticleReadRedisRepository articleReadRedisRepository, EventType supportedType,
                              ArticleReadField countField, ToLongFunction<T> articleIdOf, ToLongFunction<T> countOf,
                              EventConsumeMetrics eventConsumeMetrics) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.eventConsumeMetrics = eventConsumeMetrics;
        this.supportedType = supportedType;
        this.countField = countField;
        this.articleIdOf = articleIdOf;
        this.countOf = countOf;
    }

    @Override
    public EventType supportedType() {
        return supportedType;
    }

    @Override
    public void process(Event<T> event) {
        boolean applied = articleReadRedisRepository.applyCount(articleIdOf.applyAsLong(event.payload()), countField,
                countOf.applyAsLong(event.payload()), event.eventId());
        if (applied) {
            eventConsumeMetrics.recordApplied(event);
        } else {
            eventConsumeMetrics.recordStale(supportedType);
        }
    }
}
