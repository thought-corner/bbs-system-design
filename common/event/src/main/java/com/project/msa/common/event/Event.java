package com.project.msa.common.event;

import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 모든 서비스 간 이벤트의 봉투. Outbox 행과 Kafka 메시지 값은 이 JSON 그대로다 (D11). */
public record Event<T extends EventPayload>(Long eventId, EventType type, LocalDateTime occurredAt, T payload) {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public static <T extends EventPayload> Event<T> of(long eventId, EventType type, LocalDateTime occurredAt, T payload) {
        if (!type.payloadClass().isInstance(payload)) {
            throw new IllegalArgumentException(type + " requires " + type.payloadClass().getSimpleName()
                    + " but got " + payload.getClass().getSimpleName());
        }
        return new Event<>(eventId, type, occurredAt, payload);
    }

    public String toJson() {
        return JSON_MAPPER.writeValueAsString(this);
    }

    public static Event<EventPayload> fromJson(String json) {
        JsonNode envelope = JSON_MAPPER.readTree(json);
        EventType type = EventType.valueOf(envelope.get("type").asString());
        EventPayload payload = JSON_MAPPER.treeToValue(envelope.get("payload"), type.payloadClass());
        return new Event<>(
                envelope.get("eventId").asLong(),
                type,
                JSON_MAPPER.treeToValue(envelope.get("occurredAt"), LocalDateTime.class),
                payload);
    }
}
