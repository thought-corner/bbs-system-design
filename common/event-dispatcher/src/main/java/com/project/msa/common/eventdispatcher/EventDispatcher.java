package com.project.msa.common.eventdispatcher;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ResolvableType;

/**
 * 받은 이벤트를 타입에 맞는 처리기로 넘긴다. 맡은 처리기가 없는 이벤트는 버린다.
 * 잘못 등록된 처리기(같은 타입 둘, 페이로드 타입 불일치)는 처리할 때가 아니라 만들 때 실패시킨다.
 */
public class EventDispatcher {

    private final Map<EventType, EventProcessor<?>> processors = new EnumMap<>(EventType.class);

    public EventDispatcher(List<? extends EventProcessor<?>> processors) {
        for (EventProcessor<?> processor : processors) {
            verifyPayloadType(processor);
            EventProcessor<?> registered = this.processors.putIfAbsent(processor.supportedType(), processor);
            if (registered != null) {
                throw new IllegalStateException(processor.supportedType() + " is handled by both "
                        + registered.getClass().getSimpleName() + " and " + processor.getClass().getSimpleName());
            }
        }
    }

    public void dispatch(Event<? extends EventPayload> event) {
        EventProcessor<?> processor = processors.get(event.type());
        if (processor != null) {
            process(processor, event);
        }
    }

    public Set<EventType> supportedTypes() {
        return Collections.unmodifiableSet(processors.keySet());
    }

    /** 처리기의 T가 맡은 타입의 페이로드를 받을 수 있는지 본다. 추상 처리기를 상속해 T를 정한 경우도 풀린다. */
    private static void verifyPayloadType(EventProcessor<?> processor) {
        // raw 타입으로 구현하면 T가 풀리지 않는다. resolve()는 이것을 상한(EventPayload)으로 풀어 버리므로 따로 막는다
        if (ResolvableType.forClass(processor.getClass()).hasUnresolvableGenerics()) {
            throw new IllegalStateException("cannot resolve the payload type of " + processor.getClass().getName());
        }
        Class<?> acceptedPayload = ResolvableType.forClass(EventProcessor.class, processor.getClass())
                .getGeneric(0)
                .resolve();
        if (acceptedPayload == null) {
            throw new IllegalStateException("cannot resolve the payload type of " + processor.getClass().getName());
        }
        Class<? extends EventPayload> payloadClass = processor.supportedType().payloadClass();
        if (!acceptedPayload.isAssignableFrom(payloadClass)) {
            throw new IllegalStateException(processor.getClass().getSimpleName() + " accepts "
                    + acceptedPayload.getSimpleName() + " but " + processor.supportedType() + " carries "
                    + payloadClass.getSimpleName());
        }
    }

    /** 등록 시 T를 검사했으므로, 페이로드가 이벤트 타입과 맞는 이벤트만 넘기면 캐스트가 안전하다. */
    @SuppressWarnings("unchecked")
    private static <T extends EventPayload> void process(EventProcessor<T> processor,
                                                         Event<? extends EventPayload> event) {
        if (!event.type().payloadClass().isInstance(event.payload())) {
            throw new IllegalArgumentException(event.type() + " carries " + event.payload().getClass().getSimpleName());
        }
        processor.process((Event<T>) event);
    }
}
