package com.project.msa.common.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProducerOrderingGuardTest {

    @Test
    @DisplayName("Kafka 클라이언트 기본값(멱등·acks=all·in-flight 5)은 통과한다")
    void acceptsClientDefaults() {
        assertThatCode(() -> ProducerOrderingGuard.check(Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("멱등 프로듀서를 끄면 기동을 막는다")
    void rejectsDisabledIdempotence() {
        assertThatThrownBy(() -> ProducerOrderingGuard.check(Map.of(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("acks가 all이 아니면 기동을 막는다")
    void rejectsAcksOtherThanAll() {
        assertThatThrownBy(() -> ProducerOrderingGuard.check(Map.of(ProducerConfig.ACKS_CONFIG, "1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("재시도를 0으로 두면(클라이언트가 멱등을 조용히 끈다) 기동을 막는다")
    void rejectsZeroRetries() {
        assertThatThrownBy(() -> ProducerOrderingGuard.check(Map.of(ProducerConfig.RETRIES_CONFIG, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("연결당 동시 요청이 5를 넘으면 기동을 막는다")
    void rejectsTooManyInFlightRequests() {
        assertThatThrownBy(() -> ProducerOrderingGuard.check(
                Map.of(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 6)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("멱등 프로듀서를 끈 KafkaTemplate으로는 MessageRelay가 만들어지지 않는다")
    void messageRelayRefusesNonIdempotentProducer() {
        assertThatThrownBy(() -> MessageRelayFixture.relay(
                new ControllableKafkaTemplate(Map.of(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false")),
                new RecordingOutboxRepository()))
                .isInstanceOf(IllegalStateException.class);
    }
}
