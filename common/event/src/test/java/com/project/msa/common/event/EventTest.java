package com.project.msa.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final LocalDateTime MODIFIED_AT = LocalDateTime.of(2026, 9, 26, 10, 5, 0);

    @Test
    @DisplayName("ArticleCreated 이벤트는 JSON으로 갔다 와도 같은 값이다")
    void articleCreatedRoundTrip() {
        Event<ArticleCreatedEventPayload> created = Event.of(1L, EventType.ARTICLE_CREATED, CREATED_AT,
                new ArticleCreatedEventPayload(10L, 20L, 30L, "제목", "본문", CREATED_AT, CREATED_AT, 3L));

        Event<EventPayload> restored = Event.fromJson(created.toJson());

        assertThat(restored).isEqualTo(created);
    }

    @Test
    @DisplayName("ArticleUpdated 이벤트는 JSON으로 갔다 와도 같은 값이다")
    void articleUpdatedRoundTrip() {
        Event<ArticleUpdatedEventPayload> updated = Event.of(2L, EventType.ARTICLE_UPDATED, MODIFIED_AT,
                new ArticleUpdatedEventPayload(10L, 20L, 30L, "바뀐 제목", "바뀐 본문", CREATED_AT, MODIFIED_AT, 3L));

        Event<EventPayload> restored = Event.fromJson(updated.toJson());

        assertThat(restored).isEqualTo(updated);
    }

    @Test
    @DisplayName("ArticleDeleted 이벤트는 JSON으로 갔다 와도 같은 값이다")
    void articleDeletedRoundTrip() {
        Event<ArticleDeletedEventPayload> deleted = Event.of(3L, EventType.ARTICLE_DELETED, MODIFIED_AT,
                new ArticleDeletedEventPayload(10L, 20L, 30L, "제목", "본문", CREATED_AT, MODIFIED_AT, 2L));

        Event<EventPayload> restored = Event.fromJson(deleted.toJson());

        assertThat(restored).isEqualTo(deleted);
        assertThat(restored.type().topic()).isEqualTo("board-article");
    }

    @Test
    @DisplayName("이벤트 타입과 맞지 않는 페이로드로는 봉투를 만들 수 없다")
    void rejectsPayloadOfOtherType() {
        ArticleDeletedEventPayload deletedPayload =
                new ArticleDeletedEventPayload(10L, 20L, 30L, "제목", "본문", CREATED_AT, MODIFIED_AT, 2L);

        assertThatThrownBy(() -> Event.of(4L, EventType.ARTICLE_CREATED, CREATED_AT, deletedPayload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CommentCreated 이벤트는 JSON으로 갔다 와도 같은 값이고 board-comment 토픽으로 간다")
    void commentCreatedRoundTrip() {
        Event<CommentCreatedEventPayload> created = Event.of(5L, EventType.COMMENT_CREATED, CREATED_AT,
                new CommentCreatedEventPayload(40L, 10L, "0000100000", false, 4L));

        Event<EventPayload> restored = Event.fromJson(created.toJson());

        assertThat(restored).isEqualTo(created);
        assertThat(restored.type().topic()).isEqualTo("board-comment");
    }

    @Test
    @DisplayName("CommentDeleted 이벤트는 JSON으로 갔다 와도 같은 값이다")
    void commentDeletedRoundTrip() {
        Event<CommentDeletedEventPayload> deleted = Event.of(6L, EventType.COMMENT_DELETED, MODIFIED_AT,
                new CommentDeletedEventPayload(40L, 10L, "0000100000", true, 3L));

        Event<EventPayload> restored = Event.fromJson(deleted.toJson());

        assertThat(restored).isEqualTo(deleted);
    }
}
