package com.project.msa.common.outbox;

import org.springframework.scheduling.annotation.Scheduled;

class OutboxPollingScheduler {

    private final MessageRelay messageRelay;

    OutboxPollingScheduler(MessageRelay messageRelay) {
        this.messageRelay = messageRelay;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval:10s}")
    void pollPending() {
        messageRelay.publishPending();
    }
}
