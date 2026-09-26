package com.project.msa.articleread;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.eventdispatcher.EventDispatcher;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 이 서비스의 처리기 빈을 모아 공용 디스패처에 맡긴다. 새 이벤트를 받아도 이 클래스는 바뀌지 않는다.
 * 맡은 처리기가 없는 이벤트(조회수 등 읽기 모델과 무관한 이벤트)는 버린다.
 */
@Component
public class ArticleReadEventHandler {

    private final EventDispatcher eventDispatcher;

    ArticleReadEventHandler(List<ArticleReadEventProcessor<?>> processors) {
        this.eventDispatcher = new EventDispatcher(processors);
    }

    public void handle(Event<? extends EventPayload> event) {
        eventDispatcher.dispatch(event);
    }

    Set<EventType> supportedTypes() {
        return eventDispatcher.supportedTypes();
    }
}
