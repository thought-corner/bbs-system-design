package com.project.msa.hotarticle;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.eventdispatcher.EventConsumeMetrics;
import java.util.function.ToLongFunction;

/**
 * 점수 지표 하나의 누적값을 싣는 이벤트 처리기. 하위 클래스는 맡을 타입·지표·추출 방법만 선언한다.
 * 생성 이벤트를 받은 게시글만 게시글 생성일의 랭킹에 반영한다 (D15).
 */
abstract class ArticleCountEventProcessor<T extends EventPayload> implements HotArticleEventProcessor<T> {

    private final HotArticleRedisRepository hotArticleRedisRepository;
    private final EventType supportedType;
    private final HotArticleMetric metric;
    private final ToLongFunction<T> articleIdOf;
    private final ToLongFunction<T> countOf;
    private final EventConsumeMetrics eventConsumeMetrics;

    ArticleCountEventProcessor(HotArticleRedisRepository hotArticleRedisRepository, EventType supportedType,
                               HotArticleMetric metric, ToLongFunction<T> articleIdOf, ToLongFunction<T> countOf,
                               EventConsumeMetrics eventConsumeMetrics) {
        this.hotArticleRedisRepository = hotArticleRedisRepository;
        this.eventConsumeMetrics = eventConsumeMetrics;
        this.supportedType = supportedType;
        this.metric = metric;
        this.articleIdOf = articleIdOf;
        this.countOf = countOf;
    }

    @Override
    public EventType supportedType() {
        return supportedType;
    }

    @Override
    public void process(Event<T> event) {
        long articleId = articleIdOf.applyAsLong(event.payload());
        hotArticleRedisRepository.findCreated(articleId).ifPresent(createdDay -> {
            CountApplyResult result = hotArticleRedisRepository.applyCount(articleId, createdDay.createdDate(), metric,
                    countOf.applyAsLong(event.payload()), event.eventId());
            if (result == CountApplyResult.STALE) {
                eventConsumeMetrics.recordStale(supportedType);
            }
        });
    }
}
