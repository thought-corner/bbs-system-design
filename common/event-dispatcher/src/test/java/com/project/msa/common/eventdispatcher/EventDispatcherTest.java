package com.project.msa.common.eventdispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.msa.common.event.Event;
import com.project.msa.common.event.EventPayload;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.event.payload.ArticleViewedEventPayload;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventDispatcherTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 9, 27, 10, 0);

    @Test
    @DisplayName("이벤트는 그 타입을 맡은 처리기에만 간다")
    void dispatchesToProcessorOfEventType() {
        LikedProcessor likedProcessor = new LikedProcessor();
        UnlikedProcessor unlikedProcessor = new UnlikedProcessor();
        EventDispatcher dispatcher = new EventDispatcher(List.of(likedProcessor, unlikedProcessor));

        dispatcher.dispatch(liked());

        assertThat(likedProcessor.processed).hasSize(1);
        assertThat(unlikedProcessor.processed).isEmpty();
    }

    @Test
    @DisplayName("맡은 처리기가 없는 타입은 예외 없이 버린다")
    void ignoresTypeWithoutProcessor() {
        LikedProcessor likedProcessor = new LikedProcessor();
        EventDispatcher dispatcher = new EventDispatcher(List.of(likedProcessor));

        dispatcher.dispatch(Event.of(1L, EventType.ARTICLE_VIEWED, OCCURRED_AT, new ArticleViewedEventPayload(10L, 100L)));

        assertThat(likedProcessor.processed).isEmpty();
        assertThat(dispatcher.supportedTypes()).containsExactly(EventType.ARTICLE_LIKED);
    }

    @Test
    @DisplayName("같은 타입을 맡는 처리기가 둘이면 디스패처를 만들 수 없다")
    void rejectsTwoProcessorsForSameType() {
        assertThatThrownBy(() -> new EventDispatcher(List.of(new LikedProcessor(), new LikedProcessor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTICLE_LIKED");
    }

    @Test
    @DisplayName("T가 맡은 타입의 페이로드와 맞지 않는 처리기는 만들 때 거부한다")
    void rejectsProcessorWhosePayloadTypeDoesNotMatch() {
        assertThatThrownBy(() -> new EventDispatcher(List.of(new UnlikedPayloadButLikedType())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ArticleUnlikedEventPayload")
                .hasMessageContaining("ARTICLE_LIKED");
    }

    @Test
    @DisplayName("추상 처리기를 상속해 T를 정한 경우도 풀어서, 맞으면 받고 틀리면 거부한다")
    void resolvesPayloadTypeThroughAbstractProcessor() {
        EventDispatcher dispatcher = new EventDispatcher(List.of(new InheritedLikedProcessor()));
        assertThat(dispatcher.supportedTypes()).containsExactly(EventType.ARTICLE_LIKED);

        assertThatThrownBy(() -> new EventDispatcher(List.of(new InheritedWrongProcessor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InheritedWrongProcessor");
    }

    @Test
    @DisplayName("T가 페이로드의 상위 타입(EventPayload)인 처리기는 받아들인다")
    void acceptsProcessorOfPayloadSupertype() {
        AnyPayloadProcessor anyPayloadProcessor = new AnyPayloadProcessor();
        EventDispatcher dispatcher = new EventDispatcher(List.of(anyPayloadProcessor));

        dispatcher.dispatch(liked());

        assertThat(anyPayloadProcessor.processed).hasSize(1);
    }

    @Test
    @DisplayName("타입과 페이로드가 어긋난 이벤트는 처리기에 넘기지 않고 예외다")
    void rejectsEventWhosePayloadDoesNotMatchType() {
        LikedProcessor likedProcessor = new LikedProcessor();
        EventDispatcher dispatcher = new EventDispatcher(List.of(likedProcessor));
        // Event.of는 이 조합을 막으므로, 역직렬화 등으로 어긋난 봉투가 들어온 상황을 생성자로 만든다
        Event<EventPayload> mismatched = new Event<>(1L, EventType.ARTICLE_LIKED, OCCURRED_AT,
                new ArticleUnlikedEventPayload(10L, 1L, 0L));

        assertThatThrownBy(() -> dispatcher.dispatch(mismatched)).isInstanceOf(IllegalArgumentException.class);
        assertThat(likedProcessor.processed).isEmpty();
    }

    @Test
    @DisplayName("T를 풀 수 없는 처리기(raw 타입)는 조용히 통과시키지 않고 거부한다")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsProcessorWithUnresolvablePayloadType() {
        EventProcessor rawProcessor = new EventProcessor() {
            @Override
            public EventType supportedType() {
                return EventType.ARTICLE_LIKED;
            }

            @Override
            public void process(Event event) {
            }
        };

        List<EventProcessor<?>> rawProcessors = List.of(rawProcessor);

        assertThatThrownBy(() -> new EventDispatcher(rawProcessors))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot resolve");
    }

    private Event<ArticleLikedEventPayload> liked() {
        return Event.of(1L, EventType.ARTICLE_LIKED, OCCURRED_AT, new ArticleLikedEventPayload(10L, 1L, 1L));
    }

    private static class LikedProcessor implements EventProcessor<ArticleLikedEventPayload> {

        private final List<Event<ArticleLikedEventPayload>> processed = new ArrayList<>();

        @Override
        public EventType supportedType() {
            return EventType.ARTICLE_LIKED;
        }

        @Override
        public void process(Event<ArticleLikedEventPayload> event) {
            processed.add(event);
        }
    }

    private static class UnlikedProcessor implements EventProcessor<ArticleUnlikedEventPayload> {

        private final List<Event<ArticleUnlikedEventPayload>> processed = new ArrayList<>();

        @Override
        public EventType supportedType() {
            return EventType.ARTICLE_UNLIKED;
        }

        @Override
        public void process(Event<ArticleUnlikedEventPayload> event) {
            processed.add(event);
        }
    }

    /** 좋아요 취소 페이로드를 받는다고 선언하고 좋아요 타입을 맡는 잘못된 처리기. */
    private static class UnlikedPayloadButLikedType implements EventProcessor<ArticleUnlikedEventPayload> {

        @Override
        public EventType supportedType() {
            return EventType.ARTICLE_LIKED;
        }

        @Override
        public void process(Event<ArticleUnlikedEventPayload> event) {
        }
    }

    private static class AnyPayloadProcessor implements EventProcessor<EventPayload> {

        private final List<Event<EventPayload>> processed = new ArrayList<>();

        @Override
        public EventType supportedType() {
            return EventType.ARTICLE_LIKED;
        }

        @Override
        public void process(Event<EventPayload> event) {
            processed.add(event);
        }
    }

    /** 서비스의 카운트 처리기처럼 하위 클래스가 타입만 정하는 추상 처리기. */
    private abstract static class LikeCountProcessor<T extends EventPayload> implements EventProcessor<T> {

        @Override
        public EventType supportedType() {
            return EventType.ARTICLE_LIKED;
        }

        @Override
        public void process(Event<T> event) {
        }
    }

    private static class InheritedLikedProcessor extends LikeCountProcessor<ArticleLikedEventPayload> {
    }

    private static class InheritedWrongProcessor extends LikeCountProcessor<ArticleUnlikedEventPayload> {
    }
}
