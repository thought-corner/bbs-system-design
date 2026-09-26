package com.project.msa.common.eventdispatcher;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;

/**
 * 이벤트 타입 하나를 맡는 처리기. 새 이벤트를 받으려면 구현을 하나 더 등록하면 되고, 디스패처는 바뀌지 않는다.
 *
 * @param <T> 맡은 이벤트 타입의 페이로드 또는 그 상위 타입. 디스패처가 등록할 때 {@link EventType#payloadClass()}와 맞는지 검사한다
 */
public interface EventProcessor<T extends EventPayload> {

    EventType supportedType();

    void process(Event<T> event);
}
