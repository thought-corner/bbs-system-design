package com.project.msa.common.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** 브로커 없이 보낸 순서를 기록하고, 전송 완료 시점을 테스트가 정하는 KafkaTemplate 대역. */
class ControllableKafkaTemplate extends KafkaTemplate<String, String> {

    final List<String> sentKeys = new CopyOnWriteArrayList<>();
    final List<String> sentPayloads = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<SendResult<String, String>>> pendingSends = new CopyOnWriteArrayList<>();
    private volatile Consumer<ControllableKafkaTemplate> afterEachSend = template -> {
    };

    ControllableKafkaTemplate(Map<String, Object> producerConfig) {
        super(new DefaultKafkaProducerFactory<>(withSerializers(producerConfig)));
    }

    ControllableKafkaTemplate() {
        this(Map.of());
    }

    private static Map<String, Object> withSerializers(Map<String, Object> producerConfig) {
        Map<String, Object> config = new HashMap<>(producerConfig);
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return config;
    }

    void afterEachSend(Consumer<ControllableKafkaTemplate> hook) {
        this.afterEachSend = hook;
    }

    @Override
    public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
        CompletableFuture<SendResult<String, String>> sent = new CompletableFuture<>();
        sentKeys.add(key);
        sentPayloads.add(data);
        pendingSends.add(sent);
        afterEachSend.accept(this);
        return sent;
    }

    @Override
    public void flush() {
    }
}
