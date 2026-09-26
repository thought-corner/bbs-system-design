package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;

/**
 * 이벤트 타입 하나를 맡는 처리기. 새 이벤트를 받으려면 이 구현을 하나 더 빈으로 등록하면 된다.
 *
 * @param <T> 맡은 이벤트 타입의 페이로드 — {@link EventType#payloadClass()}와 같아야 한다
 */
interface ArticleReadEventProcessor<T extends EventPayload> {

    EventType supportedType();

    void process(Event<T> event);
}
