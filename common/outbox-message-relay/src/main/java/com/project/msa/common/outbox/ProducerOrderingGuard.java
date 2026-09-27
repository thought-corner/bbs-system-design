package com.project.msa.common.outbox;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * 커밋 직후 발행은 전송 완료를 기다리지 않고 다음 건을 보낸다.
 * 그래도 같은 게시글의 이벤트가 파티션 안에서 보낸 순서대로 쌓이려면 멱등 프로듀서여야 한다:
 * 재시도가 앞 건을 뒤로 밀지 않고(`enable.idempotence`), 모든 복제가 받은 뒤 성공으로 치며(`acks=all`),
 * 한 연결에 동시에 떠 있는 요청이 5개 이하여야 한다 (D11). 설정이 이를 어기면 기동을 막는다.
 * `retries=0`이면 클라이언트가 멱등을 조용히 끄므로(명시하지 않았을 때) 재시도도 1 이상이어야 한다.
 */
final class ProducerOrderingGuard {

    private static final int MAX_IN_FLIGHT_FOR_IDEMPOTENCE = 5;

    private ProducerOrderingGuard() {
    }

    /** Kafka 클라이언트 기본값(멱등 켜짐, acks=all, in-flight 5)을 바탕으로 명시한 값만 검사한다. */
    static void check(Map<String, Object> producerConfig) {
        String idempotence = String.valueOf(producerConfig.getOrDefault(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"));
        String acks = String.valueOf(producerConfig.getOrDefault(ProducerConfig.ACKS_CONFIG, "all"));
        int maxInFlight = Integer.parseInt(String.valueOf(producerConfig.getOrDefault(
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_FOR_IDEMPOTENCE)));
        if (!Boolean.parseBoolean(idempotence)) {
            throw new IllegalStateException("outbox relay needs enable.idempotence=true to keep per-article order");
        }
        if (!"all".equalsIgnoreCase(acks) && !"-1".equals(acks)) {
            throw new IllegalStateException("outbox relay needs acks=all for the idempotent producer: acks=" + acks);
        }
        Object retries = producerConfig.get(ProducerConfig.RETRIES_CONFIG);
        if (retries != null && Integer.parseInt(String.valueOf(retries)) < 1) {
            throw new IllegalStateException("outbox relay needs retries >= 1 for the idempotent producer: retries=" + retries);
        }
        if (maxInFlight > MAX_IN_FLIGHT_FOR_IDEMPOTENCE) {
            throw new IllegalStateException("outbox relay needs max.in.flight.requests.per.connection <= "
                    + MAX_IN_FLIGHT_FOR_IDEMPOTENCE + ": " + maxInFlight);
        }
    }
}
