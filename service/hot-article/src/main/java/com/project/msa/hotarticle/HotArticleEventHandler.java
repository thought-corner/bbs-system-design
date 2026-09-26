package com.project.msa.hotarticle;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 받은 이벤트를 타입에 맞는 처리기로 넘긴다. 처리기는 빈으로 모이므로 새 이벤트를 받아도 이 클래스는 바뀌지 않는다.
 * 맡은 처리기가 없는 이벤트(게시글 수정 등)는 점수와 무관해 버린다.
 */
@Component
public class HotArticleEventHandler {

    private final Map<EventType, HotArticleEventProcessor<?>> processors = new EnumMap<>(EventType.class);

    HotArticleEventHandler(List<HotArticleEventProcessor<?>> processors) {
        for (HotArticleEventProcessor<?> processor : processors) {
            HotArticleEventProcessor<?> registered = this.processors.putIfAbsent(processor.supportedType(), processor);
            if (registered != null) {
                throw new IllegalStateException(processor.supportedType() + " is handled by both "
                        + registered.getClass().getSimpleName() + " and " + processor.getClass().getSimpleName());
            }
        }
    }

    public void handle(Event<? extends EventPayload> event) {
        HotArticleEventProcessor<?> processor = processors.get(event.type());
        if (processor != null) {
            process(processor, event);
        }
    }

    Set<EventType> supportedTypes() {
        return Collections.unmodifiableSet(processors.keySet());
    }

    /** 처리기의 페이로드 타입은 {@link EventType#payloadClass()}와 같다. 페이로드가 그 타입인지 확인한 뒤 넘긴다. */
    @SuppressWarnings("unchecked")
    private <T extends EventPayload> void process(HotArticleEventProcessor<T> processor,
                                                  Event<? extends EventPayload> event) {
        if (!event.type().payloadClass().isInstance(event.payload())) {
            throw new IllegalArgumentException(event.type() + " carries " + event.payload().getClass().getSimpleName());
        }
        processor.process((Event<T>) event);
    }
}
